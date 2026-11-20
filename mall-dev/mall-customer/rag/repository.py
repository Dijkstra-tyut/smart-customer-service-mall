"""父块及版本目录存 PostgreSQL；通过 active_version 原子发布，绝不先删除旧知识。"""
from contextlib import asynccontextmanager
import asyncio

from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from psycopg_pool import AsyncConnectionPool

from rag.models import ParentChunk


DDL = [
    """CREATE TABLE IF NOT EXISTS rag_documents (
        id TEXT PRIMARY KEY, kb_id TEXT NOT NULL, source TEXT NOT NULL,
        active_version TEXT, fingerprint TEXT, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        UNIQUE(kb_id,source))""",
    """CREATE TABLE IF NOT EXISTS rag_versions (
        id TEXT PRIMARY KEY, document_id TEXT NOT NULL REFERENCES rag_documents(id),
        status TEXT NOT NULL CHECK(status IN ('staging','active','retired','failed')),
        fingerprint TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now())""",
    """CREATE TABLE IF NOT EXISTS rag_parent_chunks (
        id TEXT PRIMARY KEY, document_id TEXT NOT NULL REFERENCES rag_documents(id),
        version_id TEXT NOT NULL REFERENCES rag_versions(id), kb_id TEXT NOT NULL,
        source TEXT NOT NULL, section_path JSONB NOT NULL, content TEXT NOT NULL, ordinal INT NOT NULL)""",
    "CREATE INDEX IF NOT EXISTS rag_parent_version_idx ON rag_parent_chunks(version_id)",
    "CREATE INDEX IF NOT EXISTS rag_document_kb_idx ON rag_documents(kb_id)",
]


class ParentChunkRepository:
    def __init__(self, dsn: str):
        self.pool = AsyncConnectionPool(dsn, min_size=1, max_size=5, open=False,
                                        kwargs={"autocommit": True, "row_factory": dict_row})
        # 为入库 SQL 留出连接，避免多个构建任务全占用会话锁连接而互相等待。
        self._build_slots = asyncio.Semaphore(2)

    async def setup(self):
        try:
            await self.pool.open()
            await self.pool.wait(timeout=10)
            async with self.pool.connection() as conn:
                for statement in DDL:
                    await conn.execute(statement)
        except BaseException:
            await self.pool.close()
            raise

    async def close(self):
        await self.pool.close()

    @asynccontextmanager
    async def document_lock(self, document_id):
        # 会话级锁不持有数据库事务；跨进程阻止同一文档并发发布。
        async with self._build_slots, self.pool.connection() as conn:
            row = await (await conn.execute("SELECT pg_try_advisory_lock(hashtextextended(%s,0)) AS locked", (document_id,))).fetchone()
            if not row["locked"]:
                raise ValueError("该文档正在构建，请稍后重试")
            try:
                yield
            finally:
                await conn.execute("SELECT pg_advisory_unlock(hashtextextended(%s,0))", (document_id,))

    async def current(self, document_id):
        async with self.pool.connection() as conn:
            return await (await conn.execute("SELECT * FROM rag_documents WHERE id=%s", (document_id,))).fetchone()

    async def stage(self, document_id, kb_id, source, version_id, fingerprint, parents):
        async with self.pool.connection() as conn, conn.transaction():
            await conn.execute("INSERT INTO rag_documents(id,kb_id,source) VALUES(%s,%s,%s) ON CONFLICT(id) DO NOTHING", (document_id,kb_id,source))
            await conn.execute("INSERT INTO rag_versions(id,document_id,status,fingerprint) VALUES(%s,%s,'staging',%s)", (version_id,document_id,fingerprint))
            async with conn.cursor() as cursor:
                await cursor.executemany("INSERT INTO rag_parent_chunks VALUES(%s,%s,%s,%s,%s,%s,%s,%s)",
                    [(p.id,p.document_id,p.version_id,p.kb_id,p.source,Jsonb(p.section_path),p.content,p.ordinal) for p in parents])

    async def publish(self, document_id, version_id, fingerprint):
        async with self.pool.connection() as conn, conn.transaction():
            await conn.execute("UPDATE rag_versions SET status='retired' WHERE document_id=%s AND status='active'", (document_id,))
            await conn.execute("UPDATE rag_versions SET status='active' WHERE id=%s AND status='staging'", (version_id,))
            await conn.execute("UPDATE rag_documents SET active_version=%s,fingerprint=%s,updated_at=now() WHERE id=%s", (version_id,fingerprint,document_id))

    async def fail(self, version_id):
        async with self.pool.connection() as conn:
            await conn.execute("UPDATE rag_versions SET status='failed' WHERE id=%s AND status='staging'", (version_id,))

    async def active_versions(self, kb_id):
        async with self.pool.connection() as conn:
            rows = await (await conn.execute("SELECT active_version FROM rag_documents WHERE kb_id=%s AND active_version IS NOT NULL", (kb_id,))).fetchall()
            return [r["active_version"] for r in rows]

    async def parents(self, ids, kb_id):
        if not ids:
            return {}
        async with self.pool.connection() as conn:
            rows = await (await conn.execute("""SELECT p.* FROM rag_parent_chunks p JOIN rag_documents d
                ON p.document_id=d.id AND p.version_id=d.active_version
                WHERE p.id=ANY(%s) AND p.kb_id=%s""", (ids,kb_id))).fetchall()
            return {r["id"]: r for r in rows}
