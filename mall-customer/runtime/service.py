"""运行入口：绑定可信上下文、恢复图现场、激活人工任务并可靠发布结果。"""
from __future__ import annotations

import asyncio
import uuid
import weakref
from typing import Any, Literal

from langgraph.types import Command
from pydantic import BaseModel, ConfigDict, Field

from agents.contracts import HumanDecision, StrictModel
from agents.supervisor import text_violations
from runtime.backend_client import BackendError
from runtime.ports import RuntimeBackend
from runtime.context import RunContext, bind_context


class UserBackground(BaseModel):
    model_config = ConfigDict(extra="ignore")
    display_name: str = Field(default="", max_length=100)


class HistoryMessage(StrictModel):
    id: str = Field(min_length=1, max_length=128)
    database_id: str | None = Field(default=None, min_length=1, max_length=128, exclude=True)
    role: Literal["user", "assistant"]
    content: str = Field(max_length=12000)


class RunRequest(StrictModel):
    user_id: str = Field(min_length=1, max_length=64)
    session_id: str = Field(min_length=1, max_length=64)
    run_id: str = Field(min_length=1, max_length=64)
    request_id: str = Field(min_length=1, max_length=128)
    run_token: str = Field(min_length=16, max_length=1024, repr=False, exclude=True)
    revision: int = Field(ge=1)
    user_context: UserBackground = Field(default_factory=UserBackground)
    history: list[HistoryMessage] = Field(default_factory=list, max_length=100)
    long_term_memory: dict[str, Any] = Field(default_factory=dict)

    def context(self) -> RunContext:
        return RunContext(self.user_id, self.session_id, self.run_id, self.request_id, self.run_token, self.revision)


class ChatRequest(RunRequest):
    message: str = Field(min_length=1, max_length=12000)
    message_id: str = Field(min_length=1, max_length=128)


class ResumeRequest(RunRequest):
    decision: HumanDecision


def config_for(session_id: str) -> dict[str, Any]:
    return {"configurable":
                {"thread_id":
                     "session:" + session_id
                 }, "recursion_limit": 160}


# 判断图是否真正处于 interrupt 暂停状态
def checkpoint_paused(snapshot: Any) -> bool:
    # next=human_wait 也可能只是 prepare 节点已提交，不能据此宣称 interrupt 已落盘。
    return any(bool(getattr(task, "interrupts", ())) for task in getattr(snapshot, "tasks", ()))

def reply_message_id(state: dict[str, Any]) -> str:
    """检查点和 Java 消息表共用的回复编号，重试发布也不产生新消息。"""
    seed = f"{state['run_id']}:{state['revision']}:{state['status']}:{state.get('draft_revision', 0)}:{state.get('human_task_id', '')}"
    return uuid.uuid5(uuid.NAMESPACE_URL, seed + ":message").hex


# 合并对话消息列表
def merged_messages(old: list[dict], request: RunRequest, include_input: bool = False) -> list[dict[str, str]]:
    # Java 的正式会话记录优先覆盖相同 id。保留顺序，不将内部工具消息混入对话。
    # Java 补充的近期记录按其正式顺序排列；重复编号使用 Java 的正式内容。
    # database_id 兼容此前检查点直接使用 MySQL 自增编号保存的消息。
    incoming_ids = {identifier for message in request.history for identifier in (message.id, message.database_id) if identifier}
    ordered = {message["id"]: message for message in old if message["id"] not in incoming_ids}
    ordered.update({message.id: message.model_dump() for message in request.history})
    if include_input:
        ordered[request.message_id] = {"id": request.message_id, "role": "user", "content": request.message}
    selected, remaining = [], 24000
    for message in reversed(list(ordered.values())[-100:]):
        if len(message["content"]) > remaining:
            break
        selected.append(message)
        remaining -= len(message["content"])
    return list(reversed(selected))


class RunService:
    def __init__(self, graph: Any, backend: RuntimeBackend, memory=None):
        self.graph, self.backend = graph, backend
        self.memory = memory
        # 仅避免同进程重复请求重入。跨实例的活动运行由 Java 原子状态控制。
        # 一个session_id的运行实例对应一个锁 在同一时刻只能有一个 active_run，其他请求必须等待或被拒绝。
        self._locks: weakref.WeakValueDictionary[str, asyncio.Lock] = weakref.WeakValueDictionary()

    # 同一时刻只能有一个 active_run，其他请求必须等待或被拒绝。
    def lock(self, session_id: str) -> asyncio.Lock:
        lock = self._locks.get(session_id)
        if lock is None:
            lock = asyncio.Lock()
            self._locks[session_id] = lock
        return lock

    def verify_snapshot(self, state: dict[str, Any], request: RunRequest) -> None:
        if state and (state.get("user_id") != request.user_id or state.get("session_id") != request.session_id):
            raise BackendError("IDENTITY_MISMATCH", "检查点身份不匹配")

    async def start(self, request: ChatRequest) -> dict[str, Any]:
        async with self.lock(request.session_id):
            # 真正需要这些信息的代码不在 start() 里，而在好几层调用之下的节点函数里，而它们拿不到 request 这个变量。
            with bind_context(request.context()):
                await self.backend.assert_active()
                config = config_for(request.session_id)
                # 查询图的最新状态，可能是 checkpoint，也可能是正在运行的 snapshot。
                snapshot = await self.graph.aget_state(config)
                old = dict(snapshot.values or {})
                self.verify_snapshot(old, request)
                same_run = old.get("run_id") == request.run_id
                if same_run and request.revision < old.get("revision", 0):
                    raise BackendError("STALE_RUN", "输入版本落后于当前执行现场")
                # same_run 新的任务请求
                if snapshot.next and not same_run:
                    if not self.backend.business_enabled:
                        raise BackendError("RUN_BUSY", "请先恢复或取消当前运行，再发起新运行")
                    # Java 已原子准入新的 run；新运行授权验证成功即为旧现场不再活动的依据。
                    # 不能依赖上一次取消回调恰好送达 Python。
                    await self.graph.aupdate_state(config, {"status": "cancelled", "next": "end", "proposal": None}, as_node="apply_human")
                    # 获取更新后的真实执行现场
                    snapshot = await self.graph.aget_state(config)
                if snapshot.next:
                    if checkpoint_paused(snapshot) and old.get("human_task_id"):
                        return await self._publish(old, interrupted=True)
                    # 同一消息的故障重投可继续尚未完成的节点；不能注入另一条新消息覆盖现场。
                    last_ids = {message["id"] for message in old.get("messages", [])}
                    if request.message_id not in last_ids:
                        raise BackendError("RUN_BUSY", "该会话仍在处理上一条消息")
                    # 恢复 Checkpointer 中已有的待执行任务
                    await self.graph.ainvoke(None, config=config)
                elif same_run and old.get("status") in {"completed", "failed", "cancelled"}:
                    if int(old.get("revision", 0)) != request.revision:
                        raise BackendError("STALE_RUN", "已经结束的运行不能附加新输入")
                    return await self._publish(old)
                else:
                    continuation = same_run and old.get("status") == "waiting_user"
                    initial = old.copy() if continuation else {
                        "run_id": request.run_id, "session_id": request.session_id, "user_id": request.user_id,
                        "goal": request.message, "supervisor_steps": 0, "tool_calls": 0, "review_retries": 0, "active_seconds": 0.0,
                        "results": {}, "evidence": {}, "draft_revision": 0, "rejected_proposals": [], "rejected_actions": [],
                        "applied_decisions": [], "human_round": 0, "active_steps": 0,
                        "human_task_version": 0, "handled_human_message_id": "", "target_status": "",
                        "ui_actions": [],
                    }
                    # 同一会话始终恢复检查点中的对话，Java history 补入人工回复等新增消息。
                    prior_messages = list(old.get("messages", []))
                    # 最终回复独立保存在 final_reply；开始下一轮时补入消息列表。
                    if old.get("final_reply") and old.get("status") in {"completed", "waiting_user", "failed"}:
                        prior_messages.append({"id": reply_message_id(old),
                                               "role": "assistant", "content": old["final_reply"]})
                    initial.update(
                        revision=request.revision, messages=merged_messages(prior_messages, request, True),
                        user_context=request.user_context.model_dump(), status="running", next="supervisor",
                        long_term_memory=request.long_term_memory,
                        final_reply="", proposal=None, review={}, draft="", human_reason="", human_task_id="",
                        human_decision={}, error_code="", active_task={}, observations=[], tool_signatures=[], author_type="assistant",
                    )
                    await self.graph.ainvoke(initial, config=config)
                return await self._publish_snapshot(request.session_id)

    async def resume(self, request: ResumeRequest) -> dict[str, Any]:
        if request.decision.action in {"send_reply", "request_info"} and text_violations(request.decision.reply or ""):
            #TODO 文本违规可以进行处理然后大模型再生成回复，而不是直接拒绝。比如可以在大模型生成的回复前加上警告提示。
            raise BackendError("UNSAFE_REPLY", "人工回复包含需要处理的个人信息")
        async with self.lock(request.session_id):
            with bind_context(request.context()):
                await self.backend.assert_active()
                config = config_for(request.session_id)
                snapshot = await self.graph.aget_state(config)
                state = dict(snapshot.values or {})
                self.verify_snapshot(state, request)
                if state.get("run_id") != request.run_id:
                    raise BackendError("RUN_NOT_FOUND", "没有找到对应的执行现场")
                if request.decision.decision_id in state.get("applied_decisions", []):
                    # 已消费决定但后续节点故障时，继续剩余节点，不再次注入批准。
                    if snapshot.next and state.get("status") == "running":
                        # 继续执行剩余节点
                        await self.graph.ainvoke(None, config=config)
                    return await self._publish_snapshot(request.session_id)
                if not snapshot.next or state.get("human_task_id") != request.decision.task_id:
                    raise BackendError("INVALID_DECISION", "当前运行没有等待该人工决定")
                if request.decision.version < state.get("human_task_version", 1):
                    raise BackendError("STALE_RUN", "人工任务版本已经变化")
                if request.decision.action == "approve_action" and (
                    request.revision != state.get("revision") or request.decision.version != state.get("human_task_version")
                ):
                    raise BackendError("STALE_RUN", "补充信息后的旧操作不能继续批准")
                await self.graph.ainvoke(Command(
                    resume=request.decision.model_dump(),
                    update={"revision": request.revision, "user_context": request.user_context.model_dump(),
                            "long_term_memory": request.long_term_memory,
                            "messages": merged_messages(state.get("messages", []), request)},
                ), config=config)
                return await self._publish_snapshot(request.session_id)

    async def _publish_snapshot(self, session_id: str) -> dict[str, Any]:
        snapshot = await self.graph.aget_state(config_for(session_id))
        if snapshot.next and not checkpoint_paused(snapshot):
            raise BackendError("RUN_INCOMPLETE", "运行尚未保存完整的暂停或结束现场", retryable=True)
        return await self._publish(dict(snapshot.values or {}), interrupted=checkpoint_paused(snapshot))

    async def _publish(self, state: dict[str, Any], interrupted: bool = False) -> dict[str, Any]:
        status = "waiting_human" if interrupted else state["status"]
        reply = state.get("final_reply", "")
        if interrupted:
            await self.backend.activate_human(state["human_task_id"], state["human_task_version"])
            reply = ("您的请求已提交人工处理，处理结果会保存在当前会话中。" if self.backend.business_enabled
                     else "基础运行已暂停，等待人工决定；人工客服工作台待后续接入。")
        seed = f"{state['run_id']}:{state['revision']}:{status}:{state.get('draft_revision', 0)}:{state.get('human_task_id', '')}"
        message_id = reply_message_id({**state, "status": status}) if reply else None
        payload = {
            "event_id": uuid.uuid5(uuid.NAMESPACE_URL, seed + ":outcome").hex,
            "run_id": state["run_id"], "expected_revision": state["revision"], "status": status,
            "message_id": message_id, "reply": reply or None,
            "author_type": state.get("author_type", "assistant"),
            "human_task_id": state.get("human_task_id") or None,
            "actions": state.get("ui_actions", []),
        }
        result = await self.backend.publish(payload)
        if self.memory is not None and self.backend.business_enabled and status in {"completed", "waiting_user"}:
            await self.memory.consolidate(state)
        return {"run_id": state["run_id"], "status": status, "reply": reply or None,
                "message_id": message_id, "human_task_id": state.get("human_task_id") or None,
                "revision": state["revision"], "reason": state.get("human_reason") or None,
                "actions": state.get("ui_actions", []),
                **(result if isinstance(result, dict) else {})}

    async def status(self, run_id: str, session_id: str) -> dict[str, Any]:
        snapshot = await self.graph.aget_state(config_for(session_id))
        state = snapshot.values or {}
        if state.get("run_id") != run_id:
            raise BackendError("RUN_NOT_FOUND", "没有找到对应的执行现场")
        return {"run_id": run_id, "status": state.get("status"), "revision": state.get("revision"),
                "human_task_id": state.get("human_task_id") or None,
                "checkpoint_paused": checkpoint_paused(snapshot)}

    async def cancel(self, request: RunRequest) -> dict[str, Any]:
        async with self.lock(request.session_id):
            with bind_context(request.context()):
                await self.backend.confirm_cancel()
                config = config_for(request.session_id)
                snapshot = await self.graph.aget_state(config)
                self.verify_snapshot(dict(snapshot.values or {}), request)
                if not self.backend.business_enabled:
                    if snapshot.values.get("run_id") != request.run_id:
                        raise BackendError("RUN_NOT_FOUND", "没有找到对应的执行现场")
                    if snapshot.values.get("revision") != request.revision:
                        raise BackendError("STALE_RUN", "任务输入版本已经变化")
                    if snapshot.values.get("status") in {"completed", "failed"}:
                        raise BackendError("INVALID_CANCEL", "已结束的运行不能取消")
                if snapshot.values.get("run_id") == request.run_id:
                    await self.graph.aupdate_state(config, {"status": "cancelled", "next": "end", "final_reply": "", "proposal": None}, as_node="apply_human")
                return {"run_id": request.run_id, "status": "cancelled", "revision": request.revision}
