import asyncio
import json

from langchain_core.documents import Document


class MilvusChildren:
    def __init__(self, config, embeddings):
        from langchain_milvus import BM25BuiltInFunction, Milvus
        self.store = Milvus(
            embedding_function=embeddings, collection_name=config.collection,
            connection_args={"uri": config.milvus_uri, "token": config.milvus_token},
            builtin_function=BM25BuiltInFunction(output_field_names="sparse", analyzer_params={"type": "chinese"}),
            vector_field=["dense", "sparse"], consistency_level="Strong", auto_id=False,
            enable_dynamic_field=True, timeout=30,
            index_params=[{"index_type":"HNSW","metric_type":"COSINE","params":{"M":16,"efConstruction":128}},
                          {"index_type":"SPARSE_INVERTED_INDEX","metric_type":"BM25","params":{}}],
            search_params=[{"metric_type":"COSINE","params":{"ef":128}}, {"metric_type":"BM25","params":{}}],
        )

    async def add(self, children):
        docs = [Document(page_content=c.content, metadata={"child_id":c.id, "parent_id":c.parent_id,
                "version_id":c.version_id, "kb_id":c.kb_id}) for c in children]
        # 同步 SDK 在工作线程中运行，不能阻塞 FastAPI 的事件循环。
        for offset in range(0, len(docs), 64):
            ids = [c.id for c in children[offset:offset+64]]
            written = await asyncio.to_thread(self.store.add_documents, docs[offset:offset+64], ids=ids)
            if set(written) != set(ids):
                raise ValueError("向量库没有确认写入全部子块，文档版本不能发布")

    async def search(self, query, kb_id, versions, k):
        if not versions:
            return []
        from pymilvus import Function, FunctionType
        expr = f"kb_id == {json.dumps(kb_id)} and version_id in {json.dumps(versions)}"
        # Milvus 并行执行 dense、BM25 两路召回；RRF 融合后返回候选子块。
        return await asyncio.to_thread(self.store.similarity_search, query, k=k, fetch_k=k,
            expr=expr, reranker=Function(name="knowledge_rrf", input_field_names=[],
                function_type=FunctionType.RERANK, params={"reranker":"rrf","k":60}))

    async def close(self):
        await asyncio.to_thread(self.store.client.close)
