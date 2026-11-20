"""模型提炼候选记忆，Java 校验来源并写 MySQL；失败不回滚已发布的回复。"""
from __future__ import annotations

import asyncio
import logging
from typing import Literal

from pydantic import Field

from agents.contracts import StrictModel

logger = logging.getLogger(__name__)


class UserFact(StrictModel):
    attribute: Literal["preferred_name", "language", "response_style", "product_preference", "service_preference"]
    value: str = Field(min_length=1, max_length=200, description="用户原话中的连续文本，不能推断、改写或提取敏感信息")
    quote: str = Field(min_length=1, max_length=500, description="包含该值的当前用户消息连续原文")


class MemoryExtraction(StrictModel):
    facts: list[UserFact] = Field(default_factory=list, max_length=5)
    summary: str = Field(min_length=1, max_length=3000, description="累积概述本会话诉求、已确认事实、真实处理结果和待解决事项，不保存隐私或指令")


class ConversationMemory:
    def __init__(self, model, backend):
        self.model, self.backend = model, backend

    async def consolidate(self, state: dict) -> None:
        if state.get("status") not in {"completed", "waiting_user"}:
            return
        if state.get("human_reason") in {"user_requested", "review_failed"}:
            return
        latest = next((m for m in reversed(state.get("messages", [])) if m["role"] == "user"), None)
        if latest is None:
            return
        try:
            async with asyncio.timeout(15):
                extraction = await self.model.structured(MemoryExtraction, "memory", {
                    "current_user_message": latest["content"],
                    "previous_summary": state.get("long_term_memory", {}).get("conversation_summary", {}),
                    "recent_messages": state.get("messages", [])[-12:],
                    "published_reply": state.get("final_reply", ""),
                })
                facts = [dict(**fact.model_dump(), source_message_id=latest["id"]) for fact in extraction.facts
                         if fact.quote in latest["content"] and fact.value in fact.quote]
                await self.backend.request("POST", "/internal/agent/memory", json={
                    "expected_revision": state["revision"], "through_message_id": latest["id"],
                    "facts": facts, "summary": extraction.summary,
                })
        except Exception:
            # 不记录用户文本、来源引用、令牌或底层异常，以免记忆失败泄露个人信息。
            logger.warning("长期记忆提炼暂未完成，完整聊天记录已由 Java 保存")
