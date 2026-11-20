"""Java 服务适配器；商城数据及长期对话记忆只能通过 Java 服务访问。"""
from __future__ import annotations

import hmac
from typing import Any

import httpx

from runtime.context import current_context


class BackendError(Exception):
    def __init__(self, code: str, message: str, *, retryable: bool = False):
        self.code, self.public_message, self.retryable = code, message, retryable
        super().__init__(message)


class MallBackendClient:
    business_enabled = True

    def __init__(
        self, base_url: str, service_token: str, *,
        transport: httpx.AsyncBaseTransport | None = None, timeout: float = 15,
    ):
        if not service_token.strip():
            raise ValueError("必须配置 AGENT_SERVICE_TOKEN")
        self._service_token = service_token
        self._client = httpx.AsyncClient(
            base_url=base_url.rstrip("/"), timeout=timeout, transport=transport,
            follow_redirects=False,
        )

    async def close(self) -> None:
        await self._client.aclose()

    async def request(self, method: str, path: str, **kwargs: Any) -> Any:
        if not path.startswith("/internal/agent/") or ".." in path:
            raise ValueError("不允许访问未登记的商城内部路径")
        context = current_context()
        headers = {
            "X-Agent-Service-Token": self._service_token,
            "X-Agent-Run-Token": context.run_token,
            "X-Request-Id": context.request_id,
        }
        try:
            response = await self._client.request(method, path, headers=headers, **kwargs)
        except (httpx.TimeoutException, httpx.NetworkError) as exc:
            # 不传播可能包含凭证的请求对象、URL 或底层异常文本。
            raise BackendError("BACKEND_UNAVAILABLE", "商城服务暂时不可用", retryable=True) from None
        if response.status_code in (401, 403):
            raise BackendError("ACCESS_DENIED", "当前运行无权访问该业务")
        if response.status_code == 409:
            raise BackendError("STALE_RUN", "任务已变更，请刷新处理状态")
        if response.status_code >= 500:
            raise BackendError("BACKEND_UNAVAILABLE", "商城服务暂时不可用", retryable=True)
        if response.status_code >= 400:
            raise BackendError("BUSINESS_REJECTED", "业务请求未通过校验")
        try:
            envelope = response.json()
        except ValueError:
            raise BackendError("INVALID_BACKEND_RESPONSE", "商城服务返回格式异常") from None
        if not isinstance(envelope, dict) or envelope.get("code") != 200:
            code = str(envelope.get("code", "INVALID_BACKEND_RESPONSE")) if isinstance(envelope, dict) else "INVALID_BACKEND_RESPONSE"
            raise BackendError(code, "业务查询或操作未通过校验")
        return envelope.get("data")

    async def context(self) -> dict[str, Any]:
        data = await self.request("GET", "/internal/agent/context")
        if not isinstance(data, dict):
            raise BackendError("INVALID_BACKEND_RESPONSE", "商城身份上下文格式异常")
        local = current_context()
        if any(str(data.get(key)) != value for key, value in (
            ("user_id", local.user_id), ("session_id", local.session_id), ("run_id", local.run_id),
        )):
            raise BackendError("IDENTITY_MISMATCH", "运行身份与商城记录不一致")
        if int(data.get("revision", -1)) != local.revision:
            raise BackendError("STALE_RUN", "任务输入版本已经变化")
        if data.get("status") in {"cancelled", "failed"}:
            raise BackendError("RUN_INACTIVE", "任务已取消或结束")
        return data

    async def assert_active(self) -> None:
        await self.context()

    async def prepare_human(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await self.request("POST", "/internal/agent/human-tasks", json=payload)

    async def activate_human(self, task_id: str, version: int) -> None:
        await self.request("POST", f"/internal/agent/human-tasks/{task_id}/activate", json={"version": version})

    async def publish(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await self.request("POST", f"/internal/agent/runs/{payload['run_id']}/outcome", json=payload)

    async def confirm_cancel(self) -> None:
        data = await self.request("GET", "/internal/agent/context")
        local = current_context()
        if (data.get("status") != "cancelled" or str(data.get("run_id")) != local.run_id
                or str(data.get("user_id")) != local.user_id):
            raise BackendError("INVALID_CANCEL", "商城尚未确认取消该任务")


def valid_service_token(supplied: str | None, expected: str) -> bool:
    return bool(expected and supplied and hmac.compare_digest(supplied, expected))
