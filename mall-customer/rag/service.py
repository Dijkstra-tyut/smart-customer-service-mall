import asyncio
from contextlib import AsyncExitStack
from dataclasses import asdict
from pathlib import Path

from rag.pipeline import BuildPipeline
from rag.providers import DashScopeReranker, MinerULoader
from rag.repository import ParentChunkRepository
from rag.retriever import RetrievalPipeline
from rag.vectors import MilvusChildren


class KnowledgeService:
    def __init__(self, config):
        self.config = config
        self.stack = AsyncExitStack()

    async def setup(self):
        from langchain_openai import OpenAIEmbeddings
        try:
            repo = ParentChunkRepository(self.config.postgres_dsn)
            self.stack.push_async_callback(repo.close)
            await repo.setup()
            embeddings = OpenAIEmbeddings(model=self.config.embedding_model, api_key=self.config.dashscope_key,
                base_url=self.config.embedding_url, check_embedding_ctx_length=False, chunk_size=10)
            # 显式关闭 Embedding 客户端，不把网络连接留到垃圾回收阶段。
            self.stack.callback(embeddings.client._client.close)
            self.stack.push_async_callback(embeddings.async_client._client.close)
            vectors = await asyncio.to_thread(MilvusChildren,self.config,embeddings)
            self.stack.push_async_callback(vectors.close)
            loader = MinerULoader(self.config.mineru_token)
            self.stack.push_async_callback(loader.close)
            reranker = DashScopeReranker(self.config.dashscope_key,self.config.rerank_model,self.config.rerank_url)
            self.stack.push_async_callback(reranker.close)
            self.builder = BuildPipeline(self.config,loader,repo,vectors)
            self.retriever = RetrievalPipeline(self.config,repo,vectors,reranker)
            self.repository = repo
            return self
        except BaseException:
            await self.stack.aclose()
            raise

    async def close(self):
        await self.stack.aclose()

    async def build(self, source):
        root = Path(self.config.source_root).resolve()
        path = (root / source).resolve()
        if not path.is_relative_to(root) or path == root:
            raise ValueError("源文件必须位于配置的知识库源目录内")
        return await self.builder.build(path,path.relative_to(root).as_posix())

    async def search(self, query, k=5):
        return asdict(await self.retriever.search(query,k))

    async def tool(self, query):
        try:
            data = await self.search(query)
            if data["status"] == "empty":
                return {"ok":False,"code":"KNOWLEDGE_EMPTY","message":"未检索到足够相关的有效知识，请澄清或交人工核实"}
            return {"ok":True,"data":data}
        except Exception:
            # 外部服务异常不回显凭证或请求对象，也不伪装成空召回。
            return {"ok":False,"code":"KNOWLEDGE_UNAVAILABLE","message":"知识检索服务暂时不可用，请勿据此推断商城规则"}
