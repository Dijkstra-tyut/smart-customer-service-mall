"""State 保存工作状态和近期会话；长期记忆副本由 Java 的 MySQL 数据加载。"""
from typing import Any, TypedDict


class AgentState(TypedDict, total=False):
    # 只保存身份关联；run_token 等运行凭证绝不进入此结构。
    run_id: str
    session_id: str
    user_id: str
    revision: int
    messages: list[dict[str, str]]
    user_context: dict[str, str]
    long_term_memory: dict[str, Any]
    goal: str
    status: str
    next: str
    supervisor_steps: int
    tool_calls: int
    active_seconds: float
    review_retries: int
    active_task: dict[str, Any]
    active_steps: int
    observations: list[dict[str, Any]]
    tool_signatures: list[str]
    results: dict[str, dict[str, Any]]
    evidence: dict[str, dict[str, Any]]
    draft: str
    draft_revision: int
    review: dict[str, Any]
    target_status: str
    proposal: dict[str, Any] | None
    rejected_proposals: list[str]
    rejected_actions: list[str]
    handled_human_message_id: str
    human_reason: str
    human_task_id: str
    human_task_version: int
    human_round: int
    human_decision: dict[str, Any]
    applied_decisions: list[str]
    final_reply: str
    author_type: str
    error_code: str
    ui_actions: list[str]


