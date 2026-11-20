"""子 Agent 的只读工具和业务动作白名单；身份始终由 Java 的运行令牌确定。"""
from __future__ import annotations

from typing import Any, Literal

from pydantic import Field

from agents.contracts import ActionProposal, StrictModel
from runtime.backend_client import BackendError
from runtime.ports import RuntimeBackend


class NoArguments(StrictModel):
    pass


class PageArguments(StrictModel):
    page: int = Field(default=1, ge=1, le=10000, description="分页页码")
    size: int = Field(default=10, ge=1, le=20, description="每页条数，最多二十条")


class OrderListArguments(PageArguments):
    status: int | None = Field(default=None, ge=0, le=4, description="订单状态：0待支付、1已支付、2已发货、3已完成、4已取消")


class OrderArguments(StrictModel):
    order_id: int = Field(gt=0, description="已确认的商城订单编号")


class ProductSearchArguments(PageArguments):
    keyword: str = Field(min_length=1, max_length=100, description="用户需要查询的商品关键词")


class ConversationArguments(StrictModel):
    session_id: int = Field(gt=0, description="从本人会话列表中获得的会话编号，Java 会再次校验归属")
    before_id: int | None = Field(default=None, gt=0, description="上一页返回的消息游标，首次读取不传")
    size: int = Field(default=10, ge=1, le=20, description="每页消息条数")


class ProductArguments(StrictModel):
    product_id: int = Field(gt=0, description="商城商品编号")


class TicketArguments(StrictModel):
    ticket_id: str = Field(min_length=1, max_length=64, pattern=r"^[a-zA-Z0-9_-]+$", description="已确认的工单编号")


class KnowledgeArguments(StrictModel):
    query: str = Field(min_length=1, max_length=1000, description="结合当前任务和会话指代的政策查询")


class CreateTicketArguments(StrictModel):
    summary: str = Field(min_length=1, max_length=200, description="用户明确提出的办理诉求摘要")
    details: str = Field(min_length=1, max_length=4000, description="用户提供的事实及需要人工处理的事项")
    priority: Literal["low", "medium", "high", "urgent"] = Field(default="medium", description="工单优先级")


class UpdateTicketArguments(TicketArguments):
    priority: Literal["low", "medium", "high", "urgent"] | None = Field(default=None, description="新工单优先级")
    status: Literal["created", "processing", "resolved", "closed", "escalated"] | None = Field(default=None, description="请求变更的工单状态")


TOOL_ARGUMENTS = {
    "get_context": NoArguments,
    "list_orders": OrderListArguments,
    "get_order": OrderArguments,
    "search_products": ProductSearchArguments,
    "get_product": ProductArguments,
    "list_tickets": PageArguments,
    "get_ticket": TicketArguments,
    "search_knowledge": KnowledgeArguments,
    "list_conversations": PageArguments,
    "read_conversation": ConversationArguments,
}
TOOL_DESCRIPTIONS = {
    "get_context": "获取当前用户的必要背景，不返回无关个人资料",
    "list_orders": "分页查询当前用户自己的真实订单",
    "get_order": "查询已确认订单的真实详情，由商城校验订单归属",
    "search_products": "根据关键词查询商城商品",
    "get_product": "查询指定商品的实时资料",
    "list_tickets": "分页查询当前用户的真实工单",
    "get_ticket": "查询指定工单，由商城校验归属",
    "search_knowledge": "检索商城文档知识库，返回可核验的来源、章节、版本和父块正文；实时商品订单数据仍须查询 Java 服务",
    "list_conversations": "分页查看本人过往客服会话，仅在用户提及历史问题或确有衔接需要时查询",
    "read_conversation": "读取本人会话的历史消息，返回用户和客服原文；旧回复不代表当前订单、商品或政策事实",
}
AGENT_TOOLS = {
    "consultation": frozenset({"search_products", "get_product", "search_knowledge", "list_conversations", "read_conversation"}),
    "service": frozenset({"get_context", "list_orders", "get_order", "list_tickets", "get_ticket", "search_knowledge", "list_conversations", "read_conversation"}),
}
ACTION_ARGUMENTS = {
    "order_cancel": OrderArguments,
    "ticket_create": CreateTicketArguments,
    "ticket_update": UpdateTicketArguments,
}
ACTION_DESCRIPTIONS = {
    "order_cancel": "申请取消已确认的本人订单，需要人工批准后执行",
    "ticket_create": "为用户明确要求办理的事项创建真实工单，不代表退款已完成",
    "ticket_update": "申请更新本人已有工单的状态或优先级，需要人工批准",
}
# 敏感性由程序登记，模型不能提供 requires_approval=false 来放行。
SENSITIVE_ACTIONS = frozenset({"order_cancel", "ticket_update"})


def validate_proposal(proposal: ActionProposal) -> ActionProposal:
    args = ACTION_ARGUMENTS[proposal.kind].model_validate(proposal.arguments)
    if proposal.kind == "ticket_update" and args.priority is None and args.status is None:
        raise ValueError("工单更新必须指定变更内容")
    return ActionProposal(kind=proposal.kind, arguments=args.model_dump(exclude_none=True))


class BusinessTools:
    def __init__(self, backend: RuntimeBackend, knowledge=None):
        self.backend = backend
        self.knowledge = knowledge

    def descriptions(self, agent: str) -> list[dict[str, Any]]:
        if not self.backend.business_enabled:
            names = ["search_knowledge"] if self.knowledge is not None else []
            return [{"name":name,"description":TOOL_DESCRIPTIONS[name],
                     "parameters":TOOL_ARGUMENTS[name].model_json_schema()} for name in names]
        return [{"name": name, "description": TOOL_DESCRIPTIONS[name],
                 "parameters": TOOL_ARGUMENTS[name].model_json_schema()}
                for name in sorted(AGENT_TOOLS[agent])]

    def action_descriptions(self, agent: str) -> list[dict[str, Any]]:
        if agent != "service" or not self.backend.business_enabled:
            return []
        return [{"kind": name, "description": ACTION_DESCRIPTIONS[name],
                 "parameters": schema.model_json_schema()} for name, schema in ACTION_ARGUMENTS.items()]

    async def call(self, agent: str, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        if name not in AGENT_TOOLS.get(agent, ()):
            return {"ok": False, "code": "TOOL_FORBIDDEN", "message": "该子智能体没有此工具权限"}
        try:
            args = TOOL_ARGUMENTS[name].model_validate(arguments).model_dump(exclude_none=True)
        except ValueError:
            return {"ok": False, "code": "INVALID_ARGUMENTS", "message": "工具参数不完整或不符合约定，请澄清后重试"}
        if name == "search_knowledge":
            if self.knowledge is None:
                return {"ok":False,"code":"KNOWLEDGE_UNAVAILABLE","message":"知识库服务尚未启用，没有可核验的政策证据"}
            return await self.knowledge.tool(args["query"])
        if not self.backend.business_enabled:
            return {"ok": False, "code": "BUSINESS_NOT_IMPLEMENTED", "message": "基础模式未启用 Java 业务接口，当前没有真实业务数据"}
        try:
            if name == "get_context":
                data = await self.backend.context()
                data = {"user_context": data.get("user_context", {})}
            elif name == "get_order":
                data = await self.backend.request("GET", f"/internal/agent/orders/{args['order_id']}")
            elif name == "get_product":
                data = await self.backend.request("GET", f"/internal/agent/products/{args['product_id']}")
            elif name == "get_ticket":
                data = await self.backend.request("GET", f"/internal/agent/tickets/{args['ticket_id']}")
            elif name == "list_conversations":
                data = await self.backend.request("GET", "/internal/agent/conversations", params=args)
            elif name == "read_conversation":
                session_id = args.pop("session_id")
                data = await self.backend.request("GET", f"/internal/agent/conversations/{session_id}/messages", params=args)
            else:
                path = {"list_orders": "orders", "search_products": "products", "list_tickets": "tickets"}[name]
                data = await self.backend.request("GET", f"/internal/agent/{path}", params=args)
            return {"ok": True, "data": data, "historical": name in {"list_conversations", "read_conversation"}}
        except BackendError as exc:
            if exc.code in {"STALE_RUN", "RUN_INACTIVE", "IDENTITY_MISMATCH", "ACCESS_DENIED"}:
                raise
            return {"ok": False, "code": exc.code, "message": exc.public_message, "retryable": exc.retryable}
