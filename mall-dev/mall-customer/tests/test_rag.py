import asyncio
import io
import json
import os
import uuid
import zipfile
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest
from langchain_core.documents import Document

from agents.business_tools import BusinessTools
from rag.chunking import chunk_document, optimize_markdown
from rag.config import RAGConfig
from rag.pipeline import BuildPipeline
from rag.providers import DashScopeReranker, MinerULoader
from rag.repository import ParentChunkRepository
from rag.retriever import RetrievalPipeline
from rag.service import KnowledgeService
from runtime.foundation import FoundationBackend


def config(**kwargs):
    return replace(RAGConfig("unused", "mineru-test", "dashscope-test"), **kwargs)


class Repo:
    def __init__(self):
        self.active = None
        self.pending = {}
        self.rows = {}
        self.failed = []

    def document_lock(self, document_id):
        from contextlib import asynccontextmanager
        @asynccontextmanager
        async def lock():
            yield
        return lock()

    async def current(self, doc):
        return self.active

    async def stage(self, doc,kb,source,version,fingerprint,parents):
        self.pending[version] = parents

    async def publish(self, doc,version,fingerprint):
        self.active = {"active_version":version,"fingerprint":fingerprint}
        self.rows = {p.id:p.__dict__ for p in self.pending[version]}

    async def fail(self, version):
        self.failed.append(version)

    async def active_versions(self, kb):
        return [self.active["active_version"]] if self.active else []

    async def parents(self, ids, kb):
        return {key:self.rows[key] for key in ids if key in self.rows}


class Vectors:
    def __init__(self):
        self.docs = []
        self.fail = False
        self.queries = []

    async def add(self, children):
        if self.fail:
            raise RuntimeError("external failure")
        self.docs.extend(Document(page_content=c.content,metadata={**c.__dict__,"child_id":c.id}) for c in children)

    async def delete_version(self, version):
        self.docs = [d for d in self.docs if d.metadata["version_id"] != version]

    async def search(self, query,kb,versions,k):
        self.queries.append(query)
        return [d for d in self.docs if d.metadata["version_id"] in versions][:k]


def test_structural_parents_recursive_children_preserve_terms_and_table():
    markdown = "# 商城政策\n## 退货\n|条件|期限|\n|---|---|\n|未拆封|7天|\n\n" + "不得改写价格123.45元。" * 60 + "\n## 配送\n偏远地区另行确认。"
    parents, children = chunk_document(markdown,"doc","version","kb","rules.pdf",100,20)
    assert len(parents) == 2 and len(children) > len(parents)
    assert parents[0].section_path == ["商城政策","退货"]
    assert "|未拆封|7天|" in parents[0].content and "123.45" in parents[0].content
    assert all(c.parent_id in {p.id for p in parents} for c in children)
    assert [c.id for c in children] == [c.id for c in chunk_document(markdown,"doc","version","kb","rules.pdf",100,20)[1]]
    assert optimize_markdown("#标题\r\n价格100元\x00") == "# 标题\n价格100元"


@pytest.mark.asyncio
async def test_build_deduplicates_and_failed_new_version_keeps_old_visible():
    loader = SimpleNamespace(load=None)
    from unittest.mock import AsyncMock
    loader.load = AsyncMock(return_value="# 规则\n有效正文")
    repo,vectors = Repo(),Vectors()
    builder = BuildPipeline(config(),loader,repo,vectors)
    first = await builder.build(Path("rules.pdf"),"rules.pdf")
    assert first["status"] == "published"
    assert (await builder.build(Path("rules.pdf"),"rules.pdf"))["status"] == "unchanged"
    loader.load.return_value = "# 规则\n新版正文"
    vectors.fail = True
    with pytest.raises(RuntimeError):
        await builder.build(Path("rules.pdf"),"rules.pdf")
    assert repo.active["active_version"] == first["version_id"] and len(repo.failed) == 1


@pytest.mark.asyncio
async def test_multi_query_fusion_rerank_and_parent_deduplication():
    from unittest.mock import AsyncMock
    repo,vectors = Repo(),Vectors()
    builder = BuildPipeline(config(child_size=50,child_overlap=10),SimpleNamespace(load=AsyncMock(return_value="# 规则\n" + "退货规则。" * 50)),repo,vectors)
    await builder.build(Path("rules.pdf"),"rules.pdf")
    async def rank(query,texts):
        return [(i,.9) for i in reversed(range(len(texts)))]
    ranker = SimpleNamespace(rank=AsyncMock(side_effect=rank))
    retriever = RetrievalPipeline(config(),repo,vectors,ranker)
    result = await retriever.search("如何办理退货？退货期限是什么？")
    assert len(vectors.queries) == 3 and result.status == "ok" and len(result.items) == 1
    assert result.items[0]["source"] == "rules.pdf" and result.items[0]["section_path"] == ["规则"]
    ranker.rank.assert_awaited_once()


@pytest.mark.asyncio
async def test_reranker_http_contract_and_invalid_indices():
    def response(request):
        assert request.headers["Authorization"] == "Bearer secret"
        body = json.loads(request.content)
        assert body["model"] == "qwen3-rerank" and body["input"]["documents"] == ["a","b"]
        return httpx.Response(200,json={"output":{"results":[{"index":1,"relevance_score":.9},{"index":0,"relevance_score":.1}]}})
    client = httpx.AsyncClient(transport=httpx.MockTransport(response))
    ranker = DashScopeReranker("secret","qwen3-rerank","https://test/rerank",client)
    try:
        assert await ranker.rank("q",["a","b"]) == [(1,.9),(0,.1)]
    finally:
        await ranker.close()


@pytest.mark.asyncio
async def test_mineru_upload_poll_download_and_no_credentials_leaked(tmp_path):
    path = tmp_path / "rules.pdf"
    path.write_bytes(b"test-pdf")
    output = io.BytesIO()
    with zipfile.ZipFile(output,"w") as archive:
        archive.writestr("full.md","# 商城规则\n测试正文")
    def handle(request):
        if request.url.host == "mineru.net":
            assert request.headers["Authorization"] == "Bearer secret"
            if request.method == "POST":
                return httpx.Response(200,json={"code":0,"data":{"file_urls":["https://storage/upload"],"batch_id":"b1"}})
            return httpx.Response(200,json={"code":0,"data":{"extract_result":[{"state":"done","full_zip_url":"https://storage/result"}]}})
        assert "Authorization" not in request.headers
        return httpx.Response(200,content=output.getvalue() if request.method == "GET" else b"")
    loader = MinerULoader("secret",httpx.AsyncClient(transport=httpx.MockTransport(handle)))
    try:
        assert "商城规则" in await loader.load(path)
    finally:
        await loader.close()


@pytest.mark.asyncio
async def test_rag_tool_available_without_java_and_failure_is_not_evidence():
    from unittest.mock import AsyncMock
    service = KnowledgeService(config())
    service.retriever = SimpleNamespace(search=AsyncMock(side_effect=RuntimeError("secret")))
    tools = BusinessTools(FoundationBackend(),service)
    assert [d["name"] for d in tools.descriptions("consultation")] == ["search_knowledge"]
    result = await tools.call("consultation","search_knowledge",{"query":"规则"})
    assert not result["ok"] and result["code"] == "KNOWLEDGE_UNAVAILABLE" and "secret" not in str(result)
    assert (await tools.call("consultation","get_order",{"order_id":1}))["code"] == "TOOL_FORBIDDEN"


def test_real_postgres_parent_versions_publish_atomically():
    dsn = os.getenv("TEST_CHECKPOINT_POSTGRES_DSN")
    if not dsn:
        pytest.skip("需要测试 PostgreSQL")
    async def scenario():
        repo = ParentChunkRepository(dsn)
        await repo.setup()
        source = "test-" + uuid.uuid4().hex
        doc = uuid.uuid4().hex
        v1,v2 = uuid.uuid4().hex,uuid.uuid4().hex
        try:
            parents,_ = chunk_document("# 规则\n旧版",doc,v1,"test-kb",source)
            await repo.stage(doc,"test-kb",source,v1,"f1",parents)
            assert await repo.parents([parents[0].id],"test-kb") == {}
            await repo.publish(doc,v1,"f1")
            assert parents[0].id in await repo.parents([parents[0].id],"test-kb")
            new,_ = chunk_document("# 规则\n新版",doc,v2,"test-kb",source)
            await repo.stage(doc,"test-kb",source,v2,"f2",new)
            await repo.fail(v2)
            assert (await repo.current(doc))["active_version"] == v1
            assert await repo.parents([parents[0].id],"other-kb") == {}
        finally:
            async with repo.pool.connection() as conn,conn.transaction():
                await conn.execute("DELETE FROM rag_parent_chunks WHERE document_id=%s",(doc,))
                await conn.execute("DELETE FROM rag_versions WHERE document_id=%s",(doc,))
                await conn.execute("DELETE FROM rag_documents WHERE id=%s",(doc,))
            await repo.close()
    with asyncio.Runner(loop_factory=asyncio.SelectorEventLoop if os.name=="nt" else asyncio.new_event_loop) as runner:
        runner.run(scenario())


@pytest.mark.asyncio
async def test_milvus_adapter_dense_bm25_rrf_metadata_and_version_filters(monkeypatch):
    from rag.vectors import MilvusChildren
    from pymilvus import FunctionType
    captured = {}
    class Store:
        def __init__(self,**kwargs):
            captured.update(kwargs)
            self.client = SimpleNamespace(close=lambda:None)
        def add_documents(self,docs,ids):
            captured["documents"] = docs
            return ids
        def similarity_search(self,query,**kwargs):
            captured["search"] = kwargs
            return captured["documents"]
    monkeypatch.setattr("langchain_milvus.Milvus",Store)
    vectors = MilvusChildren(config(),object())
    parents,children = chunk_document("# 规则\n测试正文","d","v","k","s")
    await vectors.add(children)
    result = await vectors.search("规则","k",["v"],10)
    assert captured["vector_field"] == ["dense","sparse"]
    assert result[0].metadata["parent_id"] == parents[0].id
    assert captured["search"]["reranker"].type == FunctionType.RERANK
    assert captured["search"]["fetch_k"] == 10 and 'version_id in ["v"]' in captured["search"]["expr"]
    assert await vectors.search("规则","k",[],10) == []
    await vectors.close()


@pytest.mark.asyncio
async def test_knowledge_routes_validate_and_use_configured_service():
    from unittest.mock import AsyncMock
    from api.main import create_app
    app = create_app(runtime=SimpleNamespace(backend=FoundationBackend()),service_token="test-token")
    knowledge = SimpleNamespace(build=AsyncMock(return_value={"status":"published"}),
                                search=AsyncMock(return_value={"status":"empty","items":[]}))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url="http://test") as client:
        assert (await client.post("/api/knowledge/build",json={"source":"a.pdf"})).status_code == 401
        headers={"X-Agent-Service-Token":"test-token"}
        assert (await client.post("/api/knowledge/search",json={"query":"规则"},headers=headers)).status_code == 503
        app.state.knowledge=knowledge
        assert (await client.post("/api/knowledge/build",json={"source":"a.pdf"},headers=headers)).status_code == 200
        assert (await client.post("/api/knowledge/search",json={"query":"规则","k":100},headers=headers)).status_code == 422
        assert (await client.post("/api/knowledge/search",json={"query":"规则"},headers=headers)).json()["status"] == "empty"


@pytest.mark.asyncio
async def test_real_graph_calls_knowledge_tool_and_passes_evidence_to_review(tmp_path):
    from agents.supervisor import create_supervisor_graph
    from runtime.checkpoints import CheckpointStore
    from runtime.service import RunService,config_for
    from test_agent_runtime import ScriptedModel
    from test_foundation import chat
    from unittest.mock import AsyncMock
    model = ScriptedModel(
        supervisor=[{"action":"delegate","agent":"consultation","goal":"查询商城规则"},{"action":"compose"}],
        consultation=[{"action":"call_tool","tool":"search_knowledge","arguments":{"query":"退货规则"}},
                      {"action":"finish","summary":"已找到文档规则","evidence_ids":["e-task-1-1"]}],
        compose=["文档规则已找到。[e-task-1-1]"],review=[{"approved":True}])
    knowledge=SimpleNamespace(tool=AsyncMock(return_value={"ok":True,"data":{"items":[{"source":"rules.pdf","content":"测试规则"}]}}))
    checkpoints=CheckpointStore(backend="sqlite",sqlite_path=str(tmp_path/"rag.sqlite"))
    try:
        backend=FoundationBackend()
        graph=create_supervisor_graph(model=model,backend=backend,checkpointer=await checkpoints.setup(),knowledge=knowledge)
        result=await RunService(graph,backend).start(chat(message="查询退货规则"))
        assert result["status"] == "completed"
        state=(await graph.aget_state(config_for("s1"))).values
        assert state["evidence"]["e-task-1-1"]["data"]["items"][0]["source"] == "rules.pdf"
        assert model.calls[-1][0] == "review"
    finally:
        await checkpoints.close()


@pytest.mark.asyncio
async def test_build_source_cannot_escape_knowledge_root(tmp_path):
    service=KnowledgeService(config(source_root=str(tmp_path)))
    with pytest.raises(ValueError,match="源目录"):
        await service.build("../private.pdf")
