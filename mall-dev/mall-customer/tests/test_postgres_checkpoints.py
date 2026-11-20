"""PostgreSQL 生命周期验证；真实恢复测试通过专用测试 DSN 显式启用。"""
import asyncio
import os
import uuid
from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from psycopg.conninfo import conninfo_to_dict
from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool

from agents.supervisor import create_supervisor_graph
from runtime.checkpoints import CheckpointStore
from runtime.foundation import FoundationBackend
from runtime.service import RunService, config_for
from test_agent_runtime import ScriptedModel, resume_body
from test_foundation import chat


@pytest.mark.asyncio
async def test_default_is_postgres_and_missing_dsn_stops_startup(monkeypatch):
    monkeypatch.delenv("CHECKPOINT_BACKEND", raising=False)
    monkeypatch.delenv("CHECKPOINT_POSTGRES_DSN", raising=False)
    store = CheckpointStore()
    assert store.backend == "postgres"
    with pytest.raises(ValueError, match="CHECKPOINT_POSTGRES_DSN"):
        await store.setup()
    assert store.saver is None


@pytest.mark.asyncio
async def test_postgres_initializes_schema_once_and_closes_connection(monkeypatch, pool_stub):
    stub = pool_stub()
    store = CheckpointStore(backend="postgres", postgres_dsn="postgresql://test:secret@localhost/agent_test")
    assert await store.setup() is stub.saver
    assert await store.setup() is stub.saver
    options = conninfo_to_dict(stub.options["conninfo"])
    assert options["dbname"] == "agent_test" and options["connect_timeout"] == "10"
    assert stub.options["open"] is False
    assert stub.options["min_size"] == 1 and stub.options["max_size"] == 5
    assert stub.options["kwargs"] == {"autocommit": True, "prepare_threshold": 0, "row_factory": dict_row}
    stub.saver.setup.assert_awaited_once()
    await store.close()
    await store.close()
    assert stub.events == ["open", "wait", "saver", "setup", "close"]
    assert store.saver is None and store.pool is None


@pytest.mark.asyncio
@pytest.mark.parametrize("failure_at", ["open", "wait", "setup"])
async def test_postgres_failure_closes_resources_and_never_exposes_password(pool_stub, failure_at):
    stub = pool_stub(failure_at)
    store = CheckpointStore(backend="postgres", postgres_dsn="postgresql://user:private-password@localhost/db")
    with pytest.raises(RuntimeError, match="PostgreSQL 检查点初始化失败") as error:
        await store.setup()
    assert "private-password" not in str(error.value) and error.value.__suppress_context__
    assert store.saver is None and store.pool is None
    stub.pool.close.assert_awaited_once()
    if failure_at in {"open", "wait"}:
        assert "saver" not in stub.events


@pytest.mark.asyncio
async def test_explicit_connect_timeout_is_preserved(monkeypatch, pool_stub):
    stub = pool_stub()
    monkeypatch.setenv("CHECKPOINT_POOL_MIN_SIZE", "2")
    monkeypatch.setenv("CHECKPOINT_POOL_MAX_SIZE", "4")
    monkeypatch.setenv("CHECKPOINT_POOL_TIMEOUT_SECONDS", "3")
    store = CheckpointStore(backend="postgres", postgres_dsn="postgresql://localhost/test?connect_timeout=3")
    try:
        await store.setup()
        assert conninfo_to_dict(stub.options["conninfo"])["connect_timeout"] == "3"
        assert stub.options["min_size"] == 2 and stub.options["max_size"] == 4
        assert stub.options["timeout"] == 3
        stub.pool.wait.assert_awaited_once_with(timeout=3)
    finally:
        await store.close()


@pytest.fixture
def pool_stub(monkeypatch):
    for name in ("CHECKPOINT_POOL_MIN_SIZE", "CHECKPOINT_POOL_MAX_SIZE", "CHECKPOINT_POOL_TIMEOUT_SECONDS"):
        monkeypatch.delenv(name, raising=False)

    def install(failure_at=None):
        events, options = [], {}

        def step(name):
            def run(*args, **kwargs):
                events.append(name)
                if name == failure_at:
                    raise RuntimeError("password=private-password")
            return run

        pool = SimpleNamespace(**{name: AsyncMock(side_effect=step(name)) for name in ("open", "wait", "close")})
        saver = SimpleNamespace(setup=AsyncMock(side_effect=step("setup")))

        def create_pool(**kwargs):
            options.update(kwargs)
            return pool

        def create_saver(actual_pool):
            assert actual_pool is pool and events[-1] == "wait"
            events.append("saver")
            return saver

        monkeypatch.setattr("langgraph.checkpoint.postgres.aio.AsyncPostgresSaver", create_saver)
        monkeypatch.setattr("psycopg_pool.AsyncConnectionPool", create_pool)
        return SimpleNamespace(events=events, options=options, pool=pool, saver=saver)

    return install


@pytest.mark.asyncio
@pytest.mark.parametrize("name,value", [
    ("CHECKPOINT_POOL_MIN_SIZE", "0"), ("CHECKPOINT_POOL_MIN_SIZE", "6"),
    ("CHECKPOINT_POOL_TIMEOUT_SECONDS", "nan"), ("CHECKPOINT_POOL_TIMEOUT_SECONDS", "0"),
])
async def test_invalid_pool_configuration_never_opens_connections(monkeypatch, pool_stub, name, value):
    stub = pool_stub()
    monkeypatch.setenv(name, value)
    store = CheckpointStore(backend="postgres", postgres_dsn="postgresql://localhost/test")
    with pytest.raises(RuntimeError, match="初始化失败"):
        await store.setup()
    stub.pool.open.assert_not_awaited()
    assert store.pool is None and store.saver is None


def test_real_postgres_interrupt_survives_connection_and_graph_restart():
    dsn = os.getenv("TEST_CHECKPOINT_POSTGRES_DSN", "")
    if not dsn:
        pytest.skip("未配置专用测试库 TEST_CHECKPOINT_POSTGRES_DSN，未执行真实 PostgreSQL 恢复测试")

    async def scenario():
        request = chat(message="请转人工客服").model_copy(update={"session_id": "test-" + uuid.uuid4().hex})
        other = chat(run_id="r2", message="请转人工，我要咨询另一个问题").model_copy(
            update={"session_id": "test-" + uuid.uuid4().hex})
        config = config_for(request.session_id)
        exact = "  已收到您的问题。\n请补充说明。  "

        @asynccontextmanager
        async def runtime():
            store = CheckpointStore(backend="postgres", postgres_dsn=dsn)
            try:
                saver = await store.setup()
                backend = FoundationBackend()
                graph = create_supervisor_graph(model=ScriptedModel(), backend=backend, checkpointer=saver)
                yield RunService(graph, backend), saver
            finally:
                await store.close()

        try:
            async with runtime() as (service, saver):
                pool = saver.conn
                assert isinstance(pool, AsyncConnectionPool) and not pool.closed
                # 同时借用连接，确认 checkpointer 使用的是真实连接池。
                async with pool.connection() as first, pool.connection() as second:
                    assert first.info.backend_pid != second.info.backend_pid
                    assert first.autocommit and first.prepare_threshold == 0
                # 普通转人工现已返回按钮；此处显式构造“敏感操作待审批”现场验证真实中断持久化。
                async def pause_sensitive(current):
                    from runtime.context import bind_context
                    with bind_context(current.context()):
                        config = config_for(current.session_id)
                        await service.graph.aupdate_state(config, {
                            "run_id":current.run_id,"session_id":current.session_id,"user_id":current.user_id,
                            "revision":current.revision,"status":"running","next":"prepare_human",
                            "human_reason":"sensitive_action","messages":[{"id":current.message_id,"role":"user","content":current.message}],
                            "goal":current.message,"human_round":0,"draft_revision":0,
                        }, as_node="action_gate")
                        await service.graph.ainvoke(None, config)
                        return await service._publish_snapshot(current.session_id)
                paused, other_paused = await asyncio.gather(pause_sensitive(request), pause_sensitive(other))
                assert paused["status"] == "waiting_human"
                assert other_paused["status"] == "waiting_human"
                assert other_paused["human_task_id"] != paused["human_task_id"]
            assert pool.closed
            async with runtime() as (service, _):
                assert (await service.status(request.run_id, request.session_id))["checkpoint_paused"]
                decision = resume_body(request.__dict__, paused["human_task_id"], reply=exact)
                result = await service.resume(decision)
                assert result["status"] == "completed" and result["reply"] == exact
                assert (await service.resume(decision))["message_id"] == result["message_id"]
                assert (await service.status(other.run_id, other.session_id))["checkpoint_paused"]
            async with runtime() as (service, _):
                state = (await service.graph.aget_state(config)).values
                assert state["final_reply"] == exact and state["author_type"] == "human"
                assert request.run_token not in str(state)
        finally:
            # 仅删除本测试生成的随机 thread，不影响已有会话和表结构。
            async with runtime() as (_, saver):
                for session_id in (request.session_id, other.session_id):
                    await saver.adelete_thread(config_for(session_id)["configurable"]["thread_id"])

    # Psycopg 在 Windows 需要 SelectorEventLoop；其他平台使用默认循环。
    factory = asyncio.SelectorEventLoop if os.name == "nt" else asyncio.new_event_loop
    with asyncio.Runner(loop_factory=factory) as runner:
        runner.run(scenario())
