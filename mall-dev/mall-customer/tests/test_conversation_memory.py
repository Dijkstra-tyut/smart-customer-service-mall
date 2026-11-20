from unittest.mock import AsyncMock

import httpx
import pytest

from agents.business_tools import BusinessTools
from agents.model_gateway import INSTRUCTIONS
from agents.supervisor import SupervisorRuntime
from agents.supervisor import explicit_human_request, suggested_buttons
from agents.contracts import SupervisorDecision
from memory.conversation import ConversationMemory, MemoryExtraction
from runtime.backend_client import MallBackendClient
from runtime.backend_client import BackendError
from runtime.context import RunContext, bind_context
from runtime.service import ChatRequest, merged_messages


def test_chat_intents_produce_only_fixed_button_types():
    assert explicit_human_request([{"role":"user","content":"请转人工客服"}])
    assert explicit_human_request([{"role":"user","content":"人工客服"}])
    assert not explicit_human_request([{"role":"user","content":"不要转人工，我只想查订单"}])
    assert not explicit_human_request([{"role":"user","content":"刚才人工客服让我保留什么？"}])
    assert not explicit_human_request([{"role":"user","content":"请让你们的工作人员处理"}])
    assert suggested_buttons([{"role":"user","content":"我想查询订单并申请退货"}]) == ["view_orders","request_after_sales"]
    with pytest.raises(ValueError):
        SupervisorDecision.model_validate({"action":"compose","suggested_actions":["https://example.test"]})


@pytest.mark.asyncio
async def test_supervisor_recognizes_semantic_handoff_outside_keyword_shortcut():
    model, backend = AsyncMock(), AsyncMock()
    backend.assert_active = AsyncMock()
    model.structured.return_value = SupervisorDecision(action="request_human", reason="user_requested")
    runtime = SupervisorRuntime(model, backend)
    result = await runtime.supervisor({"messages": [{"id": "1", "role": "user", "content": "请让你们的工作人员处理"}]})
    model.structured.assert_awaited_once()
    assert result["human_reason"] == "user_requested"
    assert result["next"] == "prepare_human"


@pytest.mark.asyncio
async def test_memory_extracts_only_quoted_user_facts_and_java_receives_provenance():
    model = AsyncMock()
    model.structured.return_value = MemoryExtraction.model_validate({"facts": [
        {"attribute": "response_style", "value": "简短", "quote": "以后回复简短一点"},
        {"attribute": "preferred_name", "value": "小王", "quote": "叫我小王"},
    ], "summary": "用户希望后续回复简短。"})
    backend = AsyncMock()
    await ConversationMemory(model, backend).consolidate({
        "status": "completed", "revision": 2,
        "messages": [{"id": "17", "role": "user", "content": "以后回复简短一点"}],
        "final_reply": "好的。",
    })
    body = backend.request.call_args.kwargs["json"]
    assert body["through_message_id"] == "17"
    assert body["facts"] == [{"attribute": "response_style", "value": "简短", "quote": "以后回复简短一点", "source_message_id": "17"}]
    assert "当前用户的明确表达优先" in __import__('agents.model_gateway', fromlist=['CAPABILITY_INSTRUCTION']).CAPABILITY_INSTRUCTION


@pytest.mark.asyncio
async def test_memory_failure_does_not_replace_reply_or_run_during_interrupt():
    model, backend = AsyncMock(), AsyncMock()
    model.structured.side_effect = TimeoutError()
    memory = ConversationMemory(model, backend)
    state = {"status": "waiting_human", "messages": [{"id": "1", "role": "user", "content": "人工"}]}
    await memory.consolidate(state)
    model.structured.assert_not_called()
    state.update(status="completed", final_reply="已保存的正式回复", revision=1)
    await memory.consolidate(state)
    backend.request.assert_not_called()
    assert state["final_reply"] == "已保存的正式回复"


@pytest.mark.asyncio
async def test_history_tools_use_java_run_identity_and_do_not_accept_user_id():
    calls = []
    def handle(request):
        calls.append(request)
        return httpx.Response(200, json={"code": 200, "data": {"items": [{"content": "历史原话"}]}})
    backend = MallBackendClient("http://java.test", "service", transport=httpx.MockTransport(handle))
    try:
        with bind_context(RunContext("1", "11", "r1", "request", "token-1234567890123456", 1)):
            tools = BusinessTools(backend)
            result = await tools.call("consultation", "read_conversation", {"session_id": 12, "before_id": 40, "size": 5})
            assert result["historical"] is True
            assert calls[0].url.path == "/internal/agent/conversations/12/messages"
            assert calls[0].url.params["before_id"] == "40"
            denied = await tools.call("service", "read_conversation", {"session_id": 12, "user_id": 2})
            assert denied["code"] == "INVALID_ARGUMENTS"
            assert (await tools.call("consultation", "get_order", {"order_id": 1}))["code"] == "TOOL_FORBIDDEN"
            assert (await tools.call("service", "search_products", {"keyword": "茶"}))["code"] == "TOOL_FORBIDDEN"
    finally:
        await backend.close()


@pytest.mark.asyncio
async def test_sensitive_preflight_failure_prevents_approval_and_side_effects():
    backend = AsyncMock()
    backend.business_enabled = True
    backend.assert_active = AsyncMock()
    runtime = SupervisorRuntime(AsyncMock(), backend)
    runtime.tools.call = AsyncMock(return_value={"ok": False, "code": "BUSINESS_REJECTED"})
    result = await runtime.action_gate({"proposal": {"kind": "order_cancel", "arguments": {"order_id": 1}},
        "active_task": {"id": "t1", "agent": "service", "goal": "取消指定订单"}})
    assert result["next"] == "supervisor" and result["proposal"] is None
    assert "human_reason" not in result
    backend.prepare_human.assert_not_called()
    backend.request.assert_not_called()


def test_context_budget_keeps_latest_messages_without_changing_database_history():
    request = ChatRequest(user_id="1", session_id="11", run_id="r", request_id="q", run_token="x"*20,
                          revision=1, message="新问题", message_id="4")
    old = [{"id": str(i), "role": "user", "content": "甲" * 12000} for i in range(1, 4)]
    result = merged_messages(old, request, True)
    assert result[-1]["content"] == "新问题"
    assert sum(len(m["content"]) for m in result) <= 24000
    assert len(old) == 3
    assert "敏感" in INSTRUCTIONS["memory"]


def test_java_canonical_reply_replaces_legacy_checkpoint_database_id():
    request = ChatRequest(user_id='1', session_id='11', run_id='r2', request_id='q', run_token='x'*20,
                          revision=1, message='继续', message_id='3', history=[
                              {'id': 'reply-key', 'database_id': '2', 'role': 'assistant', 'content': '正式回复'}])
    messages = merged_messages([{'id': '2', 'role': 'assistant', 'content': '旧副本'}], request, True)
    assert [m['content'] for m in messages] == ['正式回复', '继续']
    assert 'database_id' not in messages[0]


@pytest.mark.asyncio
async def test_default_api_selects_java_and_enables_memory(tmp_path, monkeypatch):
    import api.main as main
    from runtime.service import RunService
    monkeypatch.delenv("AGENT_RUNTIME_MODE", raising=False)
    monkeypatch.setenv("MODEL_NAME", "test-model")
    monkeypatch.setenv("OPENAI_API_KEY", "test-only-no-network")
    monkeypatch.setenv("CHECKPOINT_BACKEND", "sqlite")
    monkeypatch.setenv("CHECKPOINT_SQLITE_PATH", str(tmp_path / "java.sqlite"))
    monkeypatch.setenv("RAG_ENABLED", "false")
    monkeypatch.setenv("CONVERSATION_MEMORY_ENABLED", "true")
    backend = AsyncMock()
    backend.business_enabled = True
    monkeypatch.setattr(main, "MallBackendClient", lambda *args, **kwargs: backend)
    app = main.create_app(service_token="test-service")
    async with app.router.lifespan_context(app):
        assert isinstance(app.state.runtime, RunService)
        assert app.state.runtime.backend is backend
        assert isinstance(app.state.runtime.memory, ConversationMemory)
    backend.close.assert_awaited_once()


@pytest.mark.asyncio
async def test_paid_order_cannot_enter_cancellation_approval_and_keeps_actual_evidence():
    backend = AsyncMock()
    backend.business_enabled = True
    backend.assert_active = AsyncMock()
    runtime = SupervisorRuntime(AsyncMock(), backend)
    runtime.tools.call = AsyncMock(return_value={"ok": True, "data": {"order_id": 1, "status": "paid"}})
    result = await runtime.action_gate({"proposal": {"kind": "order_cancel", "arguments": {"order_id": 1}},
        "active_task": {"id": "t1", "agent": "service", "goal": "取消订单"}})
    assert result["proposal"] is None and result["next"] == "supervisor"
    assert result["tool_calls"] == 1
    assert next(iter(result["evidence"].values()))["data"]["status"] == "paid"


@pytest.mark.asyncio
async def test_business_rejection_returns_to_supervisor_instead_of_claiming_success():
    backend = AsyncMock()
    backend.business_enabled = True
    backend.assert_active = AsyncMock()
    backend.request.side_effect = BackendError("BUSINESS_REJECTED", "状态已变化")
    runtime = SupervisorRuntime(AsyncMock(), backend)
    state = {"run_id": "r1", "proposal": {"kind": "ticket_create", "arguments": {"summary": "破损处理", "details": "用户要求协助处理商品破损"}},
             "active_task": {"id": "t1", "agent": "service", "goal": "创建售后工单"}}
    with bind_context(RunContext("1", "11", "r1", "q", "token-1234567890123456", 1)):
        result = await runtime.execute(state)
    assert result["next"] == "supervisor" and result["proposal"] is None
    assert result["results"]["t1"]["outcome"] == "failed"
    assert "evidence" not in result
