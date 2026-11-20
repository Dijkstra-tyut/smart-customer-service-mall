import asyncio

from rag.models import SearchResult


class RetrievalPipeline:
    def __init__(self, config, repository, vectors, reranker):
        self.config, self.repository, self.vectors, self.reranker = config, repository, vectors, reranker

    async def search(self, query, k=5):
        if not query.strip() or len(query) > 1000 or not 1 <= k <= 10:
            raise ValueError("检索问题或返回条数不符合约定")
        cfg = self.config
        versions = await self.repository.active_versions(cfg.kb_id)
        # 原查询与标点拆分的问题构成多查询召回，保留原查询避免改写损失语义。
        import re
        queries = list(dict.fromkeys([query, *[q.strip() for q in re.split(r"[？?；;\n]",query) if len(q.strip()) >= 4]]))[:3]
        routes = await asyncio.gather(*(self.vectors.search(q,cfg.kb_id,versions,cfg.recall_k) for q in queries))
        fused, documents = {}, {}
        for results in routes:
            for rank, doc in enumerate(results, 1):
                key = doc.metadata["child_id"]
                documents[key] = doc
                fused[key] = fused.get(key,0) + 1 / (60 + rank)
        candidates = [documents[key] for key in sorted(fused,key=fused.get,reverse=True)[:cfg.recall_k]]
        if not candidates:
            return SearchResult()
        ranked = await self.reranker.rank(query,[d.page_content for d in candidates])
        parent_scores = {}
        for index, score in ranked:
            if score >= cfg.min_score:
                parent_id = candidates[index].metadata["parent_id"]
                parent_scores[parent_id] = max(score,parent_scores.get(parent_id,0))
        ids = sorted(parent_scores,key=parent_scores.get,reverse=True)
        parents = await self.repository.parents(ids,cfg.kb_id)
        items, budget = [], 24000
        for parent_id in ids:
            if parent_id not in parents:
                continue
            parent = parents[parent_id]
            content = parent["content"]
            if len(content) > budget:
                content = content[:budget]
            items.append({"parent_id":parent_id,"source":parent["source"],"section_path":parent["section_path"],
                          "version_id":parent["version_id"],"content":content,"truncated":len(content)<len(parent["content"]),
                          "score":parent_scores[parent_id]})
            budget -= len(content)
            if len(items) >= k or budget <= 0:
                break
        return SearchResult(items,"ok" if items else "empty")
