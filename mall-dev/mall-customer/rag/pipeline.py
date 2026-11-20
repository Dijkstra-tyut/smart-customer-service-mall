import hashlib
import json
import uuid
from pathlib import Path

from rag.chunking import chunk_document, optimize_markdown


class BuildPipeline:
    def __init__(self, config, loader, repository, vectors):
        self.config, self.loader, self.repository, self.vectors = config, loader, repository, vectors

    async def build(self, path: Path, source: str):
        cfg = self.config
        document_id = uuid.uuid5(uuid.NAMESPACE_URL, f"{cfg.kb_id}:{source}").hex
        async with self.repository.document_lock(document_id):
            markdown = optimize_markdown(await self.loader.load(path))
            fingerprint = hashlib.sha256(json.dumps([markdown,cfg.child_size,cfg.child_overlap,
                cfg.embedding_model,cfg.embedding_url,cfg.collection,"structure-v1"], ensure_ascii=False).encode()).hexdigest()
            old = await self.repository.current(document_id)
            if old and old["fingerprint"] == fingerprint:
                return {"document_id": document_id, "version_id": old["active_version"], "status": "unchanged"}
            version_id = uuid.uuid4().hex
            parents, children = chunk_document(markdown,document_id,version_id,cfg.kb_id,source,cfg.child_size,cfg.child_overlap)
            await self.repository.stage(document_id,cfg.kb_id,source,version_id,fingerprint,parents)
            try:
                await self.vectors.add(children)
                await self.repository.publish(document_id,version_id,fingerprint)
            except Exception:
                try:
                    await self.repository.fail(version_id)
                except Exception:
                    pass
                # 不删除子块：发布提交成功但确认丢失时，删除会破坏已生效版本。
                # 未发布版本由 active_version 过滤隔离，孤立数据可另行清理。
                raise
            return {"document_id":document_id,"version_id":version_id,"status":"published",
                    "parent_count":len(parents),"child_count":len(children)}
