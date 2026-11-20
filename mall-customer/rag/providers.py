import asyncio
import io
import zipfile
import math
from pathlib import Path

import httpx


class MinerULoader:
    """MinerU 官方 v4：申请上传 URL、上传本地文件、轮询结果、读取 Markdown。"""
    def __init__(self, token: str, client=None, timeout=600):
        self.token, self.timeout = token, timeout
        self.client = client or httpx.AsyncClient(timeout=60, follow_redirects=False)

    async def close(self):
        await self.client.aclose()

    async def api(self, method, path, **kwargs):
        response = await self.client.request(method, "https://mineru.net/api/v4/" + path,
            headers={"Authorization": "Bearer " + self.token}, **kwargs)
        response.raise_for_status()
        body = response.json()
        if body.get("code") != 0:
            raise ValueError("MinerU 解析任务未通过校验")
        return body["data"]

    async def load(self, path: Path):
        if not self.token:
            raise ValueError("构建知识库需要 MINERU_API_TOKEN")
        if not path.is_file() or path.stat().st_size > 200 * 1024 * 1024:
            raise ValueError("源文件不存在或超过 MinerU 的 200MB 限制")
        async with asyncio.timeout(self.timeout):
            task = await self.api("POST", "file-urls/batch", json={
                "files": [{"name": path.name, "is_ocr": True}], "model_version": "vlm",
                "enable_table": True, "enable_formula": True, "language": "ch"})
            # 上传和下载使用独立请求，不将 MinerU Token 发给对象存储。
            data = await asyncio.to_thread(path.read_bytes)
            response = await self.client.put(task["file_urls"][0], content=data)
            response.raise_for_status()
            while True:
                result = await self.api("GET", "extract-results/batch/" + task["batch_id"])
                item = result["extract_result"][0]
                if item["state"] == "failed":
                    raise ValueError("MinerU 文档解析失败")
                if item["state"] == "done":
                    break
                await asyncio.sleep(2)
            async with self.client.stream("GET", item["full_zip_url"]) as response:
                response.raise_for_status()
                buffer = bytearray()
                async for part in response.aiter_bytes():
                    buffer.extend(part)
                    if len(buffer) > 200 * 1024 * 1024:
                        raise ValueError("MinerU 解析产物过大")
            with zipfile.ZipFile(io.BytesIO(buffer)) as archive:
                candidates = [f for f in archive.infolist() if f.filename.endswith(".md")]
                candidates.sort(key=lambda f: (Path(f.filename).name != "full.md", f.filename))
                if not candidates or candidates[0].file_size > 20 * 1024 * 1024:
                    raise ValueError("MinerU 未返回有效 Markdown")
                return archive.read(candidates[0]).decode("utf-8")


class DashScopeReranker:
    def __init__(self, key, model, url, client=None):
        self.key, self.model, self.url = key, model, url
        self.client = client or httpx.AsyncClient(timeout=60)

    async def close(self):
        await self.client.aclose()

    async def rank(self, query, texts):
        if not texts:
            return []
        response = await self.client.post(self.url, headers={"Authorization": "Bearer " + self.key}, json={
            "model": self.model, "input": {"query": query, "documents": texts},
            "parameters": {"top_n": len(texts), "return_documents": False}})
        response.raise_for_status()
        items = response.json()["output"]["results"]
        if any(type(r.get("index")) is not int or not 0 <= r["index"] < len(texts) for r in items):
            raise ValueError("重排模型返回无效文档索引")
        if len({r["index"] for r in items}) != len(items):
            raise ValueError("重排模型返回重复文档索引")
        ranked = [(r["index"], float(r["relevance_score"])) for r in items]
        if any(not math.isfinite(score) or not 0 <= score <= 1 for _,score in ranked):
            raise ValueError("重排模型返回无效相关性得分")
        return sorted(ranked,key=lambda r:-r[1])
