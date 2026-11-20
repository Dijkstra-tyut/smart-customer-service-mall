"""模型输出必须通过结构校验，不能用自由文本决定权限或执行状态。"""
from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class ActionProposal(StrictModel):
    kind: Literal["ticket_create", "ticket_update", "order_cancel"] = Field(description="需要办理的登记业务动作")
    arguments: dict[str, Any] = Field(description="业务动作参数，不得包含用户身份或授权信息")


class SupervisorDecision(StrictModel):
    action: Literal["delegate", "ask_user", "compose", "request_human"] = Field(description="下一步：委派子任务、追问、综合答复或请求人工")
    agent: Literal["consultation", "service"] | None = Field(default=None, description="委派的业务子智能体")
    goal: str = Field(default="", max_length=2000, description="本次子任务要解决的具体目标")
    completion_criteria: str = Field(default="", max_length=2000, description="子任务的完成条件")
    question: str = Field(default="", max_length=2000, description="信息不足时向用户提出的具体问题")
    reason: Literal["user_requested", "review_failed"] | None = Field(default=None, description="申请人工的原因；不得用此字段绕过业务授权")
    suggested_actions: list[Literal["handoff", "view_orders", "request_after_sales"]] = Field(default_factory=list, max_length=3, description="建议前端展示的固定按钮，仅表示可供用户点击，不代表业务已经执行")

    @model_validator(mode="after")
    def check_fields(self):
        if self.action == "delegate" and (self.agent is None or not self.goal.strip()):
            raise ValueError("委派必须指定子智能体和目标")
        if self.action == "ask_user" and not self.question.strip():
            raise ValueError("追问必须包含问题")
        return self


class SpecialistDecision(StrictModel):
    action: Literal["call_tool", "finish", "ask_user", "propose_action"] = Field(description="继续调用只读工具、结束子任务、追问或提出业务动作")
    tool: str = Field(default="", max_length=80, description="从已授权工具列表中选择的工具名称")
    arguments: dict[str, Any] = Field(default_factory=dict, description="对应工具的业务参数")
    summary: str = Field(default="", max_length=6000, description="已有事实及子任务结论，不得虚构查询结果")
    evidence_ids: list[str] = Field(default_factory=list, max_length=30, description="支持结论的已有证据编号，不得编造编号")
    question: str = Field(default="", max_length=2000, description="尚缺信息时的具体追问")
    proposal: ActionProposal | None = Field(default=None, description="提交执行规则校验的业务动作草案")


class ReviewVerdict(StrictModel):
    approved: bool = Field(description="草稿的事实、引用、隐私和业务承诺是否均可通过")
    issues: list[str] = Field(default_factory=list, max_length=20, description="具体问题及对应证据缺口")
    feedback: str = Field(default="", max_length=3000, description="供主管修正的明确意见")

    @model_validator(mode="after")
    def consistent_verdict(self):
        if self.approved and self.issues:
            raise ValueError("存在审查问题时不能通过")
        return self


class HumanDecision(StrictModel):
    decision_id: str = Field(min_length=1, max_length=100)
    task_id: str = Field(min_length=1, max_length=100)
    version: int = Field(ge=1)
    action: Literal["approve_action", "reject_action", "send_reply", "request_info"]
    reply: str | None = Field(default=None, max_length=12000)
    comment: str | None = Field(default=None, max_length=3000)

    @model_validator(mode="after")
    def check_reply(self):
        if self.action in {"send_reply", "request_info"} and not (self.reply or "").strip():
            raise ValueError("人工回复或补充信息请求不能为空")
        return self
