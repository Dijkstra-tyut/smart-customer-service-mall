"""第一阶段验收：无 Java、无业务数据库、无外部模型时验证真实图与 SQLite。"""
from contextlib import asynccontextmanager

import httpx
import pytest

from agents.supervisor import LoopLimits, create_supervisor_graph
from api.main import create_app
from runtime.backend_client import BackendError
from runtime.checkpoints import CheckpointStore
from runtime.foundation import FoundationBackend
from runtime.service import ChatRequest, RunRequest, RunService, config_for
from test_agent_runtime import ScriptedModel, resume_body


def chat(run_id="r1", message="你好", revision=1):
    return ChatRequest(user_id="u1", session_id="s1", run_id=run_id, request_id=f"req-{run_id}-{revision}",
                       run_token="local-test-not-business-token", revision=revision,
                       message=message, message_id=f"input-{run_id}-{revision}")


@asynccontextmanager
async def local_runtime(path, model, limits=None):
    store = CheckpointStore(backend="sqlite", sqlite_path=str(path))
    try:
        saver = await store.setup()
        backend = FoundationBackend()
        yield RunService(create_supervisor_graph(model=model, backend=backend, checkpointer=saver, limits=limits), backend)
    finally:
        await store.close()


@pytest.mark.asyncio
async def test_two_agents_run_without_business_services_and_do_not_invent_evidence(tmp_path):
    model = ScriptedModel(
        supervisor=[{"action": "delegate", "agent": "consultation", "goal": "了解商品"},
                    {"action": "delegate", "agent": "service", "goal": "了解订单"}, {"action": "compose"}],
        consultation=[{"action": "call_tool", "tool": "search_knowledge", "arguments": {"query": "售后规则"}},
                      {"action": "finish", "summary": "缺少政策证据"}],
        service=[{"action": "call_tool", "tool": "list_orders"}, {"action": "finish", "summary": "无法读取订单"}],
        compose=["当前尚不能查询商品规则及订单，请说明您希望了解的问题。"], review=[{"approved": True}],
    )
    async with local_runtime(tmp_path / "two.sqlite", model) as runtime:
        result = await runtime.start(chat(message="咨询商品和订单"))
        state = (await runtime.graph.aget_state(config_for("s1"))).values
        assert result["status"] == "completed"
        assert len(state["results"]) == 2 and state["evidence"] == {} and state["tool_calls"] == 2
        nodes = runtime.graph.get_graph().nodes
        assert "consultation" in nodes and "service" in nodes and "specialist" not in nodes
        for phase, payload in model.calls:
            if phase in {"consultation", "service"}:
                assert payload["tools"] == [] and payload["available_actions"] == []
                assert payload["capabilities"]["business_services"] is False


@pytest.mark.asyncio
async def test_handoff_button_survives_restart_without_pausing_python(tmp_path):
    path=tmp_path/'handoff.sqlite'
    request=chat(message='请转人工')
    async with local_runtime(path, ScriptedModel()) as runtime:
        result=await runtime.start(request)
        assert result['status']=='completed' and result['actions']==['handoff']
        assert (await runtime.status('r1','s1'))['checkpoint_paused'] is False
    model=ScriptedModel(supervisor=[{'action':'compose'}],compose=['请继续说明。'],review=[{'approved':True}])
    async with local_runtime(path, model) as runtime:
        await runtime.start(chat(run_id='r2',message='继续'))
        messages=model.calls[0][1]['messages']
        assert [m['role'] for m in messages]==['user','assistant','user']
        assert '转人工' in messages[1]['content']
        state=(await runtime.graph.aget_state(config_for('s1'))).values
        assert state['ui_actions']==[] and state['human_round']==0


@pytest.mark.asyncio
async def test_review_failure_interrupts_without_releasing_draft(tmp_path):
    model = ScriptedModel(supervisor=[{"action": "compose"}, {"action": "compose"}],
                          compose=["退款已完成。", "退款已完成。"],
                          review=[{"approved": False, "issues": ["没有执行证据"]}] * 2)
    async with local_runtime(tmp_path / "review.sqlite", model) as runtime:
        result = await runtime.start(chat())
        assert result["status"] == "completed" and result["actions"] == ["handoff"]
        assert "退款已完成" not in result["reply"]
        state = (await runtime.graph.aget_state(config_for("s1"))).values
        assert state["review_retries"] == 2 and "转人工" in state["final_reply"]


@pytest.mark.asyncio
async def test_handoff_offer_allows_a_new_run_but_never_changes_identity(tmp_path):
    model=ScriptedModel(supervisor=[{'action':'compose'}],compose=['您好。'],review=[{'approved':True}])
    async with local_runtime(tmp_path/'new.sqlite',model) as runtime:
        assert (await runtime.start(chat(message='转人工')))['actions']==['handoff']
        assert (await runtime.start(chat(run_id='r2')))['status']=='completed'
        with pytest.raises(BackendError,match='身份不匹配'):
            await runtime.start(chat(run_id='r3').model_copy(update={'user_id':'other'}))


@pytest.mark.asyncio
async def test_unavailable_action_returns_to_supervisor_without_fake_success(tmp_path):
    model = ScriptedModel(
        supervisor=[{"action": "delegate", "agent": "service", "goal": "取消订单"}, {"action": "compose"}],
        service=[{"action": "propose_action", "proposal": {"kind": "order_cancel", "arguments": {"order_id": 1}}}],
        compose=["订单办理能力尚未接入，当前没有取消订单。"], review=[{"approved": True}],
    )
    async with local_runtime(tmp_path / "disabled.sqlite", model) as runtime:
        result = await runtime.start(chat(message="取消订单"))
        state = (await runtime.graph.aget_state(config_for("s1"))).values
        assert result["status"] == "completed" and state["evidence"] == {}
        assert state["results"]["task-1"]["outcome"] == "unavailable" and state["proposal"] is None


@pytest.mark.asyncio
async def test_supervisor_budget_is_finite_without_java(tmp_path):
    model = ScriptedModel(supervisor=[{"action": "delegate", "agent": "consultation", "goal": "了解诉求"}],
                          consultation=[{"action": "finish", "summary": "尚需补充信息"}])
    async with local_runtime(tmp_path / "limit.sqlite", model, LoopLimits(supervisor_steps=1)) as runtime:
        result = await runtime.start(chat())
        assert result["status"] == "failed"
        assert (await runtime.graph.aget_state(config_for("s1"))).values["error_code"] == "LOOP_LIMIT"


@pytest.mark.asyncio
async def test_explicit_foundation_lifespan_does_not_use_java(tmp_path, monkeypatch):
    import api.main as main

    monkeypatch.setenv("AGENT_RUNTIME_MODE", "foundation")
    monkeypatch.setenv("MODEL_NAME", "test-model")
    monkeypatch.setenv("OPENAI_API_KEY", "test-only-no-network")
    monkeypatch.setenv("CHECKPOINT_BACKEND", "sqlite")
    monkeypatch.setenv("CHECKPOINT_SQLITE_PATH", str(tmp_path / "api.sqlite"))
    model = ScriptedModel(supervisor=[{"action": "compose"}], compose=["您好。"], review=[{"approved": True}])
    monkeypatch.setattr(main, "ModelGateway", lambda *args, **kwargs: model)
    monkeypatch.setattr(main, "MallBackendClient", lambda *args, **kwargs: pytest.fail("基础模式不能初始化 Java 客户端"))
    app = create_app(service_token="local-access-token")
    async with app.router.lifespan_context(app):
        assert isinstance(app.state.runtime.backend, FoundationBackend)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://local") as client:
            headers = {"X-Agent-Service-Token": "local-access-token"}
            request = chat()
            body = request.model_dump(mode="json") | {"run_token": request.run_token}
            response = await client.post("/api/chat", json=body, headers=headers)
            assert response.status_code == 200 and '"type": "message"' in response.text
            tools = (await client.get("/api/tools", headers=headers)).json()
            assert tools["status"] == "disabled" and tools["agents"] == {"consultation": [], "service": []}


@pytest.mark.asyncio
async def test_checkpoint_configuration_failure_never_falls_back_to_memory():
    store = CheckpointStore(backend="unknown")
    with pytest.raises(ValueError, match="仅支持"):
        await store.setup()
    assert store.saver is None


@pytest.mark.asyncio
@pytest.mark.parametrize("limits,consultation,expected", [
    (LoopLimits(specialist_steps=1),
     [{"action": "call_tool", "tool": "search_knowledge", "arguments": {"query": "规则"}}],
     "步数上限"),
    (LoopLimits(),
     [{"action": "call_tool", "tool": "search_knowledge", "arguments": {"query": "规则"}}] * 2,
     "重复查询"),
])
async def test_specialist_limit_and_repeated_queries_return_control(tmp_path, limits, consultation, expected):
    model = ScriptedModel(supervisor=[{"action": "delegate", "agent": "consultation", "goal": "查询规则"},
                                      {"action": "compose"}], consultation=consultation,
                          compose=["当前没有可核验的规则资料，请补充问题。"], review=[{"approved": True}])
    async with local_runtime(tmp_path / "bounded.sqlite", model, limits) as runtime:
        assert (await runtime.start(chat()))["status"] == "completed"
        state = (await runtime.graph.aget_state(config_for("s1"))).values
        assert expected in state["results"]["task-1"]["summary"]
        assert state["tool_calls"] == 1 and state["supervisor_steps"] == 2


@pytest.mark.asyncio
async def test_cancel_cannot_rewrite_completed_checkpoint(tmp_path):
    model = ScriptedModel(supervisor=[{"action": "compose"}], compose=["您好。"], review=[{"approved": True}])
    async with local_runtime(tmp_path / "terminal.sqlite", model) as runtime:
        request = chat()
        await runtime.start(request)
        cancel = RunRequest.model_validate({k: v for k, v in request.__dict__.items() if k not in {"message", "message_id"}})
        with pytest.raises(BackendError, match="已结束"):
            await runtime.cancel(cancel)
        assert (await runtime.status("r1", "s1"))["status"] == "completed"
