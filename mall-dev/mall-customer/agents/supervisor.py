"""受控的两层 Agent 循环；图节点保存关键决策、观察与人工中断现场。"""
from __future__ import annotations

import asyncio
import hashlib
import inspect
import json
import re
import time
import uuid
from dataclasses import dataclass
from typing import Any

from langgraph.graph import END, START, StateGraph
from langgraph.types import interrupt

from agents.business_tools import BusinessTools, SENSITIVE_ACTIONS, validate_proposal
from agents.contracts import ActionProposal, HumanDecision, ReviewVerdict, SpecialistDecision, SupervisorDecision
from agents.state import AgentState
from agents.specialists import SPECIALISTS
from agents.model_gateway import ModelAuthenticationFailure
from runtime.ports import RuntimeBackend
from runtime.context import current_context
from runtime.backend_client import BackendError


@dataclass(frozen=True)
class LoopLimits:
    supervisor_steps: int = 8
    specialist_steps: int = 6
    tool_calls: int = 16
    review_retries: int = 2
    active_seconds: float = 180


def fingerprint(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()


def action_target(proposal: dict[str, Any]) -> str:
    args = proposal["arguments"]
    return f"{proposal['kind']}:{args.get('order_id', args.get('ticket_id', 'current_request'))}"


def latest_user_id(state: AgentState) -> str:
    return next((m["id"] for m in reversed(state.get("messages", [])) if m["role"] == "user"), "")


def explicit_human_request(messages: list[dict[str, str]]) -> bool:
    text = next((m["content"] for m in reversed(messages) if m["role"] == "user"), "")
    if re.search(r"(?:不需要|不用|不要|不想).{0,6}(?:人工|真人)", text):
        return False
    # 仅快捷处理明确的转接请求；提到“人工客服”本身不代表希望转接。
    # 其他表达（如“请让工作人员处理”）交给主管模型做语义判断。
    return bool(
        re.search(r"(?:转接?|找|联系|接入|需要|想要|要)(?:一下|个|位|一位)?(?:人工|真人)", text)
        or re.fullmatch(r"\s*(?:请)?(?:人工|真人)(?:客服)?[。！!？?\s]*", text)
    )


def suggested_buttons(messages: list[dict[str, str]]) -> list[str]:
    text = next((m["content"] for m in reversed(messages) if m["role"] == "user"), "")
    buttons = []
    if "订单" in text:
        buttons.append("view_orders")
    if any(word in text for word in ("退货", "退款", "换货", "售后")):
        buttons.append("request_after_sales")
    return buttons


def text_violations(text: str) -> list[str]:
    rules = [
        (r"(?<!\d)1[3-9]\d{9}(?!\d)", "回复含未脱敏的手机号"),
        (r"[\w.+-]+@[\w-]+\.[\w.-]+", "回复含未脱敏的邮箱"),
        (r"(?<!\d)\d{17}[\dXx](?!\d)", "回复可能含未脱敏的身份证号"),
    ]
    return [message for pattern, message in rules if re.search(pattern, text)]


class SupervisorRuntime:
    def __init__(self, model: Any, backend: RuntimeBackend, limits: LoopLimits | None = None, knowledge=None):
        self.model, self.backend = model, backend
        self.tools = BusinessTools(backend, knowledge)
        self.limits = limits or LoopLimits()

    def payload(self, state: AgentState) -> dict[str, Any]:
        return {
            "messages": state.get("messages", []), "user_context": state.get("user_context", {}),
            "long_term_memory": state.get("long_term_memory", {}),
            "goal": state.get("goal", ""), "results": state.get("results", {}),
            "evidence": state.get("evidence", {}), "review": state.get("review", {}),
            "rejected_proposals": state.get("rejected_proposals", []),
            "capabilities": {"business_services": self.backend.business_enabled,
                             "knowledge_search": self.tools.knowledge is not None,
                             "note": "文档知识不能替代实时业务查询；无证据时说明能力边界或追问"},
        }

    def fail(self, code: str, message: str) -> dict[str, Any]:
        return {"status": "failed", "next": "end", "error_code": code, "final_reply": message, "ui_actions": ["handoff"]}

    def finish_subtask(self, state: AgentState, outcome: str, summary: str, **extra: Any) -> dict[str, Any]:
        task = state["active_task"]
        result = {"subtask_id": task["id"], "agent": task["agent"], "outcome": outcome, "summary": summary, **extra}
        return {"results": {**state.get("results", {}), task["id"]: result}, "next": "supervisor"}

    async def supervisor(self, state: AgentState) -> dict[str, Any]:
        await self.backend.assert_active()
        if explicit_human_request(state.get("messages", [])) and state.get("handled_human_message_id") != latest_user_id(state):
            return {"human_reason": "user_requested", "next": "prepare_human"}
        steps = state.get("supervisor_steps", 0)
        if steps >= self.limits.supervisor_steps:
            return self.fail("LOOP_LIMIT", "本次自动处理未能完成，请稍后重试或联系人工客服。")
        try:
            decision = await self.model.structured(SupervisorDecision, "supervisor", self.payload(state))
        except (ValueError, TimeoutError):
            return {**self.fail("INVALID_DECISION", "暂时无法可靠理解处理步骤，请稍后重试。"), "supervisor_steps": steps + 1}
        update: dict[str, Any] = {"supervisor_steps": steps + 1, "ui_actions": list(dict.fromkeys([*decision.suggested_actions, *suggested_buttons(state.get("messages", []))]))[:3]}
        if decision.action == "delegate":
            prior = list(state.get("results", {}).values())
            if len(prior) >= 2 and all(r.get("goal") == decision.goal for r in prior[-2:]):
                return {**update, **self.fail("NO_PROGRESS", "目前没有获得新的有效信息，请补充具体问题后再试。")}
            task_id = f"task-{steps + 1}"
            update.update(active_task={"id": task_id, "agent": decision.agent, "goal": decision.goal,
                                       "completion_criteria": decision.completion_criteria},
                          active_steps=0, observations=[], tool_signatures=[], next=decision.agent)
        elif decision.action == "ask_user":
            update.update(draft=decision.question, draft_revision=state.get("draft_revision", 0) + 1,
                          target_status="waiting_user", next="review")
        elif decision.action == "request_human":
            if decision.reason == "review_failed" and state.get("review_retries", 0) >= self.limits.review_retries:
                update.update(human_reason="review_failed", next="prepare_human")
            elif decision.reason == "user_requested":
                # 结构化语义识别覆盖“请让你们的工作人员处理”等非关键词表达。
                if state.get("handled_human_message_id") == latest_user_id(state):
                    update.update(next="compose")
                else:
                    update.update(human_reason="user_requested", next="prepare_human")
            else:
                return {**update, **self.fail("INVALID_HUMAN_REASON", "当前请求需要进一步说明，暂时无法完成自动处理。")}
        else:
            update.update(next="compose")
        return update

    async def specialist(self, state: AgentState) -> dict[str, Any]:
        task = state["active_task"]
        steps = state.get("active_steps", 0)
        if steps >= self.limits.specialist_steps:
            return self.finish_subtask(state, "failed", "专业处理已达到步数上限，不能继续重复调用。", goal=task["goal"])
        payload = {**self.payload(state), "subtask": task, "specialist": SPECIALISTS[task["agent"]], "observations": state.get("observations", []),
                   "tools": self.tools.descriptions(task["agent"]),
                   "available_actions": self.tools.action_descriptions(task["agent"])}
        try:
            decision = await self.model.structured(SpecialistDecision, task["agent"], payload)
        except (ValueError, TimeoutError):
            return {**self.finish_subtask(state, "failed", "专业处理返回了无效结果，未执行任何业务操作。", goal=task["goal"]), "active_steps": steps + 1}
        update: dict[str, Any] = {"active_steps": steps + 1}
        known = state.get("evidence", {})
        if any(key not in known for key in decision.evidence_ids):
            return {**update, **self.finish_subtask(state, "failed", "专业处理引用了不存在的证据，结果未被采纳。", goal=task["goal"])}
        if decision.action == "call_tool":
            signature = fingerprint([decision.tool, decision.arguments])
            if signature in state.get("tool_signatures", []):
                return {**update, **self.finish_subtask(state, "failed", "重复查询没有带来新信息，请更换处理步骤或澄清。", goal=task["goal"])}
            update.update(active_task={**task, "tool": decision.tool, "arguments": decision.arguments}, next="tool")
        elif decision.action == "ask_user":
            if not decision.question.strip():
                return {**update, **self.finish_subtask(state, "failed", "缺少具体追问内容。", goal=task["goal"])}
            update.update(self.finish_subtask(state, "needs_input", decision.summary, question=decision.question, goal=task["goal"]))
        elif decision.action == "propose_action":
            if task["agent"] != "service" or decision.proposal is None:
                return {**update, **self.finish_subtask(state, "failed", "当前子任务无权提出该操作。", goal=task["goal"])}
            try:
                proposal = validate_proposal(decision.proposal).model_dump()
            except ValueError:
                return {**update, **self.finish_subtask(state, "needs_input", "操作参数不完整，未执行业务。", goal=task["goal"])}
            if fingerprint(proposal) in state.get("rejected_proposals", []) or action_target(proposal) in state.get("rejected_actions", []):
                return {**update, **self.finish_subtask(state, "failed", "此操作已经被人工拒绝，不得自动重新办理。", goal=task["goal"])}
            update.update(proposal=proposal, next="action_gate")
        else:
            update.update(self.finish_subtask(state, "completed", decision.summary, evidence_ids=decision.evidence_ids, goal=task["goal"]))
        return update

    async def tool(self, state: AgentState) -> dict[str, Any]:
        if state.get("tool_calls", 0) >= self.limits.tool_calls:
            return self.fail("TOOL_LIMIT", "本次查询次数已达到上限，请补充问题后重试。")
        task = state["active_task"]
        result = await self.tools.call(task["agent"], task["tool"], task["arguments"])
        observation = {"tool": task["tool"], "arguments": task["arguments"], **result}
        evidence = dict(state.get("evidence", {}))
        if result["ok"]:
            evidence_id = f"e-{task['id']}-{state['active_steps']}"
            evidence[evidence_id] = {"id": evidence_id, "source": task["tool"], "arguments": task["arguments"], "data": result["data"],
                                     "historical": result.get("historical", False)}
            observation["evidence_id"] = evidence_id
        return {"observations": [*state.get("observations", []), observation], "evidence": evidence,
                "tool_calls": state.get("tool_calls", 0) + 1,
                "tool_signatures": [*state.get("tool_signatures", []), fingerprint([task["tool"], task["arguments"]])],
                "next": task["agent"]}

    async def action_gate(self, state: AgentState) -> dict[str, Any]:
        await self.backend.assert_active()
        if not self.backend.business_enabled:
            return self.finish_subtask(state, "unavailable", "基础模式未启用 Java 业务动作，尚未执行任何操作。",
                                       goal=state["active_task"]["goal"]) | {"proposal": None}
        proposal = validate_proposal(ActionProposal.model_validate(state["proposal"]))
        if proposal.kind in SENSITIVE_ACTIONS:
            if state.get("tool_calls", 0) >= self.limits.tool_calls:
                return self.fail("TOOL_LIMIT", "目标状态尚未核实，本次查询次数已达到上限，未提交审批。")
            # 不让模型跳过目标核实，也不沿用上轮对话中的订单状态。
            tool, argument = (("get_order", "order_id") if proposal.kind == "order_cancel" else ("get_ticket", "ticket_id"))
            result = await self.tools.call("service", tool, {argument: proposal.arguments[argument]})
            checked: dict[str, Any] = {"tool_calls": state.get("tool_calls", 0) + 1}
            if not result["ok"]:
                return self.finish_subtask(state, "failed", "目标状态查询未通过，未提交审批或执行业务。", goal=state["active_task"]["goal"]) | {"proposal": None, **checked}
            key = "preflight-" + fingerprint(proposal.model_dump())[:24]
            checked["evidence"] = {**state.get("evidence", {}), key: {"id": key, "source": tool,
                        "arguments": {argument: proposal.arguments[argument]}, "data": result["data"]}}
            if proposal.kind == "order_cancel" and result["data"].get("status") != "pending_pay":
                return self.finish_subtask(state, "failed", "当前取消接口只支持待支付订单；该订单不符合取消条件，未提交审批。",
                                           goal=state["active_task"]["goal"], evidence_ids=[key]) | {"proposal": None, **checked}
            return {**checked, "human_reason": "sensitive_action", "next": "prepare_human"}
        return {"next": "execute"}

    async def execute(self, state: AgentState) -> dict[str, Any]:
        await self.backend.assert_active()
        proposal = validate_proposal(ActionProposal.model_validate(state["proposal"]))
        if fingerprint(proposal.model_dump()) in state.get("rejected_proposals", []) or action_target(proposal.model_dump()) in state.get("rejected_actions", []):
            return self.fail("ACTION_REJECTED", "该操作已被拒绝，未执行。")
        decision = state.get("human_decision", {})
        approval = state.get("human_task_id") if decision.get("action") == "approve_action" else None
        if proposal.kind in SENSITIVE_ACTIONS and not approval:
            return self.fail("APPROVAL_REQUIRED", "此操作必须经人工批准。")
        operation_id = uuid.uuid5(uuid.NAMESPACE_URL, state["run_id"] + ":" + fingerprint(proposal.model_dump())).hex
        try:
            result = await self.backend.request("POST", "/internal/agent/actions", json={
                "operation_id": operation_id, "expected_revision": current_context().revision,
                **proposal.model_dump(), "approval_task_id": approval,
            })
        except BackendError as error:
            if error.retryable or error.code in {"ACCESS_DENIED", "STALE_RUN", "IDENTITY_MISMATCH", "RUN_INACTIVE"}:
                raise
            # 正常的业务拒绝交回主管解释；网络中断保留现场，避免重复执行未知结果。
            return self.finish_subtask(state, "failed", "商城未接受该操作：" + error.public_message,
                                       goal=state.get("active_task", {}).get("goal", state.get("goal", ""))) | {"proposal": None}
        evidence_id = f"action-{operation_id}"
        evidence = {**state.get("evidence", {}), evidence_id: {"id": evidence_id, "source": "business_action", "data": result}}
        task = state.get("active_task") or {"id": "human-action", "agent": "service", "goal": state.get("goal", "")}
        results = {**state.get("results", {}), task["id"]: {"subtask_id": task["id"], "agent": task["agent"], "outcome": "completed",
                   "summary": "业务服务已返回操作结果，请根据实际结果答复。", "data": result, "evidence_ids": [evidence_id], "goal": task["goal"]}}
        return {"results": results, "evidence": evidence, "proposal": None, "next": "supervisor"}

    async def compose(self, state: AgentState) -> dict[str, Any]:
        try:
            draft = await self.model.text("compose", self.payload(state))
        except (ValueError, TimeoutError):
            return self.fail("COMPOSE_FAILED", "暂时无法生成可靠的回复，请稍后重试。")
        return {"draft": draft, "draft_revision": state.get("draft_revision", 0) + 1,
                "target_status": "completed", "next": "review"}

    async def review(self, state: AgentState) -> dict[str, Any]:
        issues = text_violations(state.get("draft", ""))
        cited = re.findall(r"\[([a-zA-Z][a-zA-Z0-9_-]{0,100})\]", state.get("draft", ""))
        if any(citation not in state.get("evidence", {}) for citation in cited):
            issues.append("回复引用了不存在的证据编号")
        if issues:
            verdict = ReviewVerdict(approved=False, issues=issues, feedback="请删除无必要的完整个人信息后重新生成回复。")
        else:
            try:
                verdict = await self.model.structured(ReviewVerdict, "review", {**self.payload(state), "draft": state.get("draft", "")})
            except (ValueError, TimeoutError):
                verdict = ReviewVerdict(approved=False, issues=["审查结果格式无效或审查超时"], feedback="无法确认回复安全性，不能按通过处理。")
        if verdict.approved:
            return {"review": verdict.model_dump(), "status": state.get("target_status", "completed"),
                    "final_reply": state["draft"], "author_type": "assistant", "next": "end"}
        retries = state.get("review_retries", 0) + 1
        if retries >= self.limits.review_retries:
            return {"review": verdict.model_dump(), "review_retries": retries, "human_reason": "review_failed", "next": "prepare_human"}
        return {"review": verdict.model_dump(), "review_retries": retries, "next": "supervisor"}

    async def prepare_human(self, state: AgentState) -> dict[str, Any]:
        await self.backend.assert_active()
        if state["human_reason"] != "sensitive_action":
            # 普通转人工是用户界面动作；点击后由 Java 接管，Python 不保留真人聊天的暂停现场。
            return {"status": "completed", "next": "end", "ui_actions": ["handoff"],
                    "final_reply": "可以点击下方“转人工”按钮，由人工客服继续处理。",
                    "author_type": "assistant", "proposal": None}
        round_id = state.get("human_round", 0) + 1
        task_id = uuid.uuid5(uuid.NAMESPACE_URL, f"{state['run_id']}:human:{round_id}:{state.get('draft_revision', 0)}:{state['human_reason']}").hex
        result = await self.backend.prepare_human({
            "task_id": task_id, "run_id": state["run_id"], "revision": current_context().revision,
            "reason": state["human_reason"], "draft": state.get("draft", ""),
            "action_proposal": state.get("proposal"), "evidence": list(state.get("evidence", {}).values()),
            "review": state.get("review", {}),
        })
        return {"human_task_id": result["task_id"], "human_task_version": result["version"],
                "human_round": round_id, "status": "waiting_human", "next": "human_wait",
                "handled_human_message_id": latest_user_id(state) if state["human_reason"] == "user_requested" else state.get("handled_human_message_id", "")}

    def human_wait(self, state: AgentState) -> dict[str, Any]:
        # 该节点没有中断前业务副作用，恢复重放不会重复创建待审任务。
        value = interrupt({"task_id": state["human_task_id"], "version": state["human_task_version"], "reason": state["human_reason"]})
        decision = HumanDecision.model_validate(value)
        if decision.task_id != state["human_task_id"]:
            raise ValueError("人工决定不属于当前中断任务")
        return {"human_decision": decision.model_dump(), "status": "running", "next": "apply_human"}

    def apply_human(self, state: AgentState) -> dict[str, Any]:
        decision = HumanDecision.model_validate(state["human_decision"])
        applied = [*state.get("applied_decisions", []), decision.decision_id]
        base: dict[str, Any] = {"applied_decisions": applied}
        if decision.action in {"send_reply", "request_info"}:
            if text_violations(decision.reply or ""):
                raise ValueError("人工回复包含未通过校验的个人信息")
            return {**base, "final_reply": decision.reply or "", "author_type": "human",
                    "status": "completed" if decision.action == "send_reply" else "waiting_user", "proposal": None, "next": "end"}
        if decision.action == "approve_action":
            if not state.get("proposal"):
                raise ValueError("当前任务没有可批准的操作")
            return {**base, "next": "execute"}
        rejected = [*state.get("rejected_proposals", [])]
        rejected_actions = [*state.get("rejected_actions", [])]
        if state.get("proposal"):
            rejected.append(fingerprint(state["proposal"]))
            rejected_actions.append(action_target(state["proposal"]))
        results = dict(state.get("results", {}))
        results[f"rejected-{state['human_round']}"] = {"outcome": "failed", "summary": "人工已拒绝该业务动作，不得自动换参数绕过。", "reason": decision.comment or "人工拒绝"}
        return {**base, "rejected_proposals": rejected, "rejected_actions": rejected_actions,
                "proposal": None, "results": results, "human_decision": {}, "next": "compose"}


def create_supervisor_graph(*, model: Any, backend: RuntimeBackend, checkpointer: Any, limits: LoopLimits | None = None, knowledge=None):
    if checkpointer is None:
        raise ValueError("必须提供持久化检查点，禁止静默退回进程内存")
    runtime = SupervisorRuntime(model, backend, limits, knowledge)
    graph = StateGraph(AgentState)
    nodes = {
        "supervisor": runtime.supervisor, "consultation": runtime.specialist,
        "service": runtime.specialist, "tool": runtime.tool,
        "action_gate": runtime.action_gate, "execute": runtime.execute, "compose": runtime.compose,
        "review": runtime.review, "prepare_human": runtime.prepare_human,
        "human_wait": runtime.human_wait, "apply_human": runtime.apply_human,
    }
    for name, function in nodes.items():
        def timed_node(function, name):
            async def invoke(state):
                spent = state.get("active_seconds", 0.0)
                remaining = runtime.limits.active_seconds - spent
                # 暂停本身及人工终稿应用不消耗外部等待时间，也不能在建单后阻止中断落盘。
                if name in {"human_wait", "apply_human"}:
                    return function(state)
                if remaining <= 0:
                    return runtime.fail("ACTIVE_TIME_LIMIT", "自动处理时间已达到上限，请稍后重试或联系人工客服。")
                started = time.monotonic()
                try:
                    async with asyncio.timeout(remaining):
                        result = await function(state) if inspect.iscoroutinefunction(function) else function(state)
                except TimeoutError:
                    result = runtime.fail("ACTIVE_TIME_LIMIT", "自动处理时间已达到上限，请稍后重试或联系人工客服。")
                except ModelAuthenticationFailure:
                    result = runtime.fail("MODEL_AUTHENTICATION_FAILED", "智能客服暂时不可用，请稍后重试或联系人工客服。")
                return {**result, "active_seconds": spent + time.monotonic() - started}
            return invoke
        graph.add_node(name, timed_node(function, name))
    graph.add_edge(START, "supervisor")
    # 显式限定每个节点可达的后继，图展示与实际控制流保持一致。
    routes = {
        "supervisor": ("consultation", "service", "compose", "review", "prepare_human", "end"),
        "consultation": ("tool", "action_gate", "supervisor", "end"),
        "service": ("tool", "action_gate", "supervisor", "end"),
        "tool": ("consultation", "service", "end"),
        "action_gate": ("prepare_human", "execute", "supervisor", "end"),
        "execute": ("supervisor", "end"),
        "compose": ("review", "end"),
        "review": ("supervisor", "prepare_human", "end"),
        "prepare_human": ("human_wait", "end"),
        "human_wait": ("apply_human",),
        "apply_human": ("execute", "compose", "end"),
    }
    for name, targets in routes.items():
        graph.add_conditional_edges(name, lambda state: state["next"],
                                    {target: END if target == "end" else target for target in targets})
    return graph.compile(checkpointer=checkpointer)
