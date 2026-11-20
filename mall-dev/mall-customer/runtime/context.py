"""可信身份只在请求执行期间存在，禁止进入图状态或模型输入。"""
from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, field
from typing import Iterator


@dataclass(frozen=True, slots=True)
class RunContext:
    user_id: str
    session_id: str
    run_id: str
    request_id: str
    run_token: str = field(repr=False)
    revision: int = 1


_run_context: ContextVar[RunContext] = ContextVar("agent_run_context")


def current_context() -> RunContext:
    try:
        return _run_context.get()
    except LookupError as exc:
        raise RuntimeError("缺少可信运行上下文") from exc


@contextmanager
def bind_context(context: RunContext) -> Iterator[RunContext]:
    token = _run_context.set(context)
    try:
        yield context
    finally:
        _run_context.reset(token)