import os
from dataclasses import dataclass, field


@dataclass(frozen=True)
class RAGConfig:
    postgres_dsn: str = field(repr=False)
    mineru_token: str = field(repr=False)
    dashscope_key: str = field(repr=False)
    milvus_token: str = field(default="", repr=False)
    milvus_uri: str = "http://localhost:19530"
    collection: str = "mall_knowledge_children_v1"
    kb_id: str = "mall_public"
    embedding_model: str = "text-embedding-v4"
    embedding_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    rerank_model: str = "qwen3-rerank"
    rerank_url: str = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"
    child_size: int = 500
    child_overlap: int = 80
    recall_k: int = 30
    min_score: float = 0.2
    source_root: str = "./data/knowledge"

    @classmethod
    def from_env(cls):
        result = cls(
            postgres_dsn=os.getenv("RAG_POSTGRES_DSN") or os.getenv("CHECKPOINT_POSTGRES_DSN", ""),
            mineru_token=os.getenv("MINERU_API_TOKEN", ""), dashscope_key=os.getenv("DASHSCOPE_API_KEY", ""),
            milvus_token=os.getenv("MILVUS_TOKEN", ""), milvus_uri=os.getenv("MILVUS_URI", cls.milvus_uri),
            collection=os.getenv("RAG_COLLECTION", cls.collection), kb_id=os.getenv("RAG_KB_ID", cls.kb_id),
            embedding_model=os.getenv("RAG_EMBEDDING_MODEL", cls.embedding_model),
            embedding_url=os.getenv("RAG_EMBEDDING_URL", cls.embedding_url),
            rerank_model=os.getenv("RAG_RERANK_MODEL", cls.rerank_model),
            rerank_url=os.getenv("RAG_RERANK_URL", cls.rerank_url),
            source_root=os.getenv("RAG_SOURCE_ROOT", cls.source_root),
            child_size=int(os.getenv("RAG_CHILD_SIZE", "500")),
            child_overlap=int(os.getenv("RAG_CHILD_OVERLAP", "80")),
            recall_k=int(os.getenv("RAG_RECALL_K", "30")),
            min_score=float(os.getenv("RAG_MIN_RERANK_SCORE", "0.2")),
        )
        if not result.postgres_dsn or not result.dashscope_key:
            raise ValueError("启用 RAG 需要 PostgreSQL 连接和 DASHSCOPE_API_KEY")
        if not (0 <= result.child_overlap < result.child_size <= 2000 and 1 <= result.recall_k <= 100
                and 0 <= result.min_score <= 1):
            raise ValueError("RAG 分块或检索参数无效")
        return result
