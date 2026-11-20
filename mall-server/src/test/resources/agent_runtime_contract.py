"""跨语言契约验证：实际 Java HTTP 接口和持久化，模型使用确定性测试替身。"""
from __future__ import annotations

import asyncio
import json
import sys
import tempfile
from collections import deque
from contextlib import AsyncExitStack
from pathlib import Path

import httpx

from agents.supervisor import create_supervisor_graph
from runtime.backend_client import MallBackendClient
from runtime.checkpoints import CheckpointStore
from runtime.service import ChatRequest, ResumeRequest, RunService, config_for
from runtime.context import bind_context
from memory.conversation import ConversationMemory


class ScriptedModel:
    def __init__(self):
        self.supervisor = deque([
            {"action": "delegate", "agent": "consultation", "goal": "查询商品", "completion_criteria": "得到真实商品信息"},
            {"action": "delegate", "agent": "service", "goal": "查询本人订单", "completion_criteria": "只查询当前用户订单"},
            {"action": "compose"},
        ])
        self.specialist = {
            "consultation": deque([
                {"action": "call_tool", "tool": "get_product", "arguments": {"product_id": 7}},
                {"action": "finish", "summary": "已查到测试商品", "evidence_ids": []},
            ]),
            "service": deque([
                {"action": "call_tool", "tool": "list_orders", "arguments": {"page": 1, "size": 10}},
                {"action": "finish", "summary": "已查到当前用户订单", "evidence_ids": []},
            ]),
        }

    async def structured(self, schema, phase, payload):
        if phase == "supervisor":
            value = self.supervisor.popleft()
        elif phase == "review":
            value = {"approved": True, "issues": [], "feedback": "业务事实均来自实际查询"}
        elif phase == "memory":
            value = {"facts": [{"attribute": "response_style", "value": "简短", "quote": "以后回复简短一点"}],
                     "summary": "用户希望简短回复，已查询商品和本人订单。"}
        else:
            value = self.specialist[phase].popleft()
        return schema.model_validate(value)

    async def text(self, phase, payload):
        assert phase == "compose"
        evidence = json.dumps(payload["evidence"], ensure_ascii=False, default=str)
        assert "测试商品" in evidence, evidence
        assert "ALICE-ORDER" in evidence
        assert "BOB-PRIVATE-ORDER" not in evidence
        assert "private-password" not in evidence
        return "已查询到测试商品，以及您本人的订单。"


class NoModelCalls:
    async def structured(self, *args, **kwargs):
        raise AssertionError("人工请求或人工确认终稿不应调用模型")

    async def text(self, *args, **kwargs):
        raise AssertionError("人工终稿不得被模型改写")


class ApprovalModel:
    async def structured(self, schema, phase, payload):
        value = ({"action":"delegate","agent":"service","goal":"取消本人订单"} if phase == "supervisor"
                 else {"action":"propose_action","proposal":{"kind":"order_cancel","arguments":{"order_id":102}}})
        return schema.model_validate(value)


async def main():
    base_url, artifact_dir = sys.argv[1:]
    credential = sys.stdin.read()
    async with httpx.AsyncClient(base_url=base_url, headers={"X-Agent-Service-Token": credential}, timeout=20) as java:
        async def test_call(path, value=None):
            response = await java.post(path, json=value or {})
            assert response.status_code == 200, response.text
            envelope = response.json()
            assert envelope["code"] == 200, envelope
            return envelope["data"]

        backend = MallBackendClient(base_url, credential)
        try:
            with tempfile.TemporaryDirectory(prefix="runtime-", dir=artifact_dir) as directory:
                sqlite_path = str(Path(directory) / "checkpoints.sqlite")
                async with AsyncExitStack() as resources:
                    checkpoints = CheckpointStore("sqlite", sqlite_path)
                    resources.push_async_callback(checkpoints.close)
                    saver = await checkpoints.setup()
                    model = ScriptedModel()
                    runtime = RunService(create_supervisor_graph(model=model, backend=backend, checkpointer=saver), backend,
                                         memory=ConversationMemory(model, backend))
                    request = ChatRequest.model_validate(await test_call("/test/begin", {"message": "以后回复简短一点，帮我看看这个商品和我的订单"}))
                    result = await runtime.start(request)
                    assert result["status"] == "completed", result
                    view = await test_call("/test/view")
                    assert view["run"]["status"] == "completed"
                    assert sum(message["content"] == result["reply"] for message in view["messages"]) == 1
                    state = (await runtime.graph.aget_state(config_for(request.session_id))).values
                    assert len(state["results"]) == 2
                    assert request.run_token not in json.dumps(state, ensure_ascii=False, default=str)
                    assert credential not in json.dumps(state, ensure_ascii=False, default=str)

                    # A new logical run shares the conversation, but cannot reuse old approval or credentials.
                    offer = ChatRequest.model_validate(await test_call("/test/begin", {"message": "请转人工客服"}))
                    runtime = RunService(create_supervisor_graph(model=NoModelCalls(), backend=backend, checkpointer=saver), backend)
                    offered = await runtime.start(offer)
                    assert offered["status"] == "completed" and offered["actions"] == ["handoff"]
                    assert not (await runtime.status(offer.run_id, offer.session_id))["checkpoint_paused"]
                    manual = ChatRequest.model_validate(await test_call("/test/begin", {"message": "请取消订单102"}))
                    assert manual.long_term_memory["profile"][0]["value"] == "简短"
                    assert manual.long_term_memory["conversation_summary"]["summary"]
                    with bind_context(manual.context()):
                        conversations = await backend.request("GET", "/internal/agent/conversations")
                        assert conversations["total"] == 1
                        history = await backend.request("GET", "/internal/agent/conversations/11/messages", params={"size": 2})
                        assert history["has_more"] is True
                        assert history["items"][-1]["content"] == "请取消订单102"
                    runtime = RunService(create_supervisor_graph(model=ApprovalModel(), backend=backend, checkpointer=saver), backend)
                    interrupted = await runtime.start(manual)
                    assert interrupted["status"] == "waiting_human", interrupted
                    assert (await runtime.status(manual.run_id, manual.session_id))["checkpoint_paused"] is True
                    await checkpoints.close()

                    # Reopen the SQLite file and graph; no original ContextVar or model instance survives.
                    checkpoints = CheckpointStore("sqlite", sqlite_path)
                    resources.push_async_callback(checkpoints.close)
                    saver = await checkpoints.setup()
                    runtime = RunService(create_supervisor_graph(model=NoModelCalls(), backend=backend, checkpointer=saver), backend)
                    exact_reply = "  人工已核对您的诉求。\n请保留这条回复。  "
                    task_id = interrupted["human_task_id"]
                    await test_call("/test/decision", {"task_id": task_id, "decision": {
                        "decisionId": "cross-service-human-decision", "version": 1,
                        "action": "send_reply", "reply": exact_reply,
                    }})
                    resume_payload = await test_call("/test/claim", {"task_id": task_id})
                    # Java 的交付尝试编号仅供本地 lease fencing，正式 HTTP 适配器也会移除此字段。
                    resume_payload.pop("_delivery_attempt", None)
                    resume = ResumeRequest.model_validate(resume_payload)
                    resumed = await runtime.resume(resume)
                    assert resumed["status"] == "completed", resumed
                    assert resumed["reply"] == exact_reply
                    repeated = await runtime.resume(resume)
                    assert repeated["message_id"] == resumed["message_id"]
                    view = await test_call("/test/view")
                    assert view["run"]["status"] == "completed"
                    assert sum(message["content"] == exact_reply for message in view["messages"]) == 1
                    assert resume.run_token not in json.dumps((await runtime.graph.aget_state(config_for(resume.session_id))).values, default=str)
                    await checkpoints.close()
                    print("CROSS_SERVICE_OK: 双子任务、用户隔离、真实HTTP落库、偏好摘要写入与下一轮加载、历史分页、冷启动人工恢复、重复决定去重")
        finally:
            await backend.close()


if __name__ == "__main__":
    asyncio.run(main())
