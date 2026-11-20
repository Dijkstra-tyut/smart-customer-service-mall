"""第一阶段本地运行控制：执行现场以 checkpointer 为准，不连接业务服务。"""
from typing import Any

from runtime.backend_client import BackendError
from runtime.context import current_context


class FoundationBackend:
    business_enabled = False

    async def assert_active(self) -> None:
        current_context()

    async def prepare_human(self, payload: dict[str, Any]) -> dict[str, Any]:
        await self.assert_active()
        return {"task_id": payload["task_id"], "version": 1}

    async def activate_human(self, task_id: str, version: int) -> None:
        await self.assert_active()

    async def publish(self, payload: dict[str, Any]) -> dict[str, Any]:
        await self.assert_active()
        # 离线模式只返回运行结果；正式落库与人工投递由 Java 适配器负责。
        return {}

    async def confirm_cancel(self) -> None:
        await self.assert_active()

    async def request(self, method: str, path: str, **kwargs: Any) -> Any:
        raise BackendError("BUSINESS_NOT_IMPLEMENTED", "基础模式未启用 Java 服务，当前不能查询或办理真实业务")

    async def close(self) -> None:
        pass
