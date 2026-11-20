"""仅保存执行现场；记忆策略另议，人工等待不得静默降级或短 TTL 清除。"""
from __future__ import annotations

import math
import os
from contextlib import AsyncExitStack
from pathlib import Path
from typing import Any


class CheckpointStore:
    def __init__(self, backend: str | None = None, sqlite_path: str | None = None,
                 postgres_dsn: str | None = None):
        # 显式传入 sqlite_path 仅用于本地隔离测试；正常启动默认 PostgreSQL。
        self.backend = backend or ("sqlite" if sqlite_path is not None else os.getenv("CHECKPOINT_BACKEND", "postgres"))
        self.sqlite_path = sqlite_path or os.getenv("CHECKPOINT_SQLITE_PATH", "./data/checkpoints.sqlite")
        self._postgres_dsn = postgres_dsn if postgres_dsn is not None else os.getenv("CHECKPOINT_POSTGRES_DSN", "")
        self._stack = AsyncExitStack()
        self.saver: Any = None
        self.pool: Any = None

    async def setup(self) -> Any:
        if self.saver is not None:
            return self.saver
        try:
            if self.backend == "postgres":
                if not self._postgres_dsn.strip():
                    raise ValueError("请在 .env 中填写 CHECKPOINT_POSTGRES_DSN，目标 PostgreSQL 数据库需提前创建")
                from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
                from psycopg.conninfo import conninfo_to_dict, make_conninfo
                from psycopg.rows import dict_row
                from psycopg_pool import AsyncConnectionPool

                try:
                    options = conninfo_to_dict(self._postgres_dsn)
                    options.setdefault("connect_timeout", "10")
                    min_size = int(os.getenv("CHECKPOINT_POOL_MIN_SIZE", "1"))
                    max_size = int(os.getenv("CHECKPOINT_POOL_MAX_SIZE", "5"))
                    timeout = float(os.getenv("CHECKPOINT_POOL_TIMEOUT_SECONDS", "10"))
                    if not (1 <= min_size <= max_size and math.isfinite(timeout) and timeout > 0):
                        raise ValueError("无效的检查点连接池配置")
                    self.pool = AsyncConnectionPool(
                        conninfo=make_conninfo(**options), min_size=min_size, max_size=max_size,
                        timeout=timeout, open=False,
                        kwargs={"autocommit": True, "prepare_threshold": 0, "row_factory": dict_row},
                    )
                    # 先登记清理，确保 open/wait/setup 任一步骤失败也能释放资源。
                    self._stack.push_async_callback(self.pool.close)
                    await self.pool.open()
                    await self.pool.wait(timeout=timeout)
                    self.saver = AsyncPostgresSaver(self.pool)
                    await self.saver.setup()
                except Exception:
                    # DSN 可能含密码，不回显底层连接或解析异常。
                    raise RuntimeError("PostgreSQL 检查点初始化失败，请检查连接配置、连接池参数、数据库状态和建表权限") from None
            elif self.backend == "sqlite":
                from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver
                Path(self.sqlite_path).parent.mkdir(parents=True, exist_ok=True)
                self.saver = await self._stack.enter_async_context(AsyncSqliteSaver.from_conn_string(self.sqlite_path))
                await self.saver.setup()
            else:
                raise ValueError("CHECKPOINT_BACKEND 仅支持 postgres，sqlite 仅供隔离测试使用")
            return self.saver
        except BaseException:
            await self._stack.aclose()
            self.saver = None
            self.pool = None
            raise

    async def close(self) -> None:
        await self._stack.aclose()
        self.saver = None
        self.pool = None
