"""商城 Agent 运行入口，Java 提供业务查询、人工审批及 MySQL 对话记忆。"""
from __future__ import annotations

import asyncio
import json
import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from dotenv import load_dotenv
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse

from agents.business_tools import AGENT_TOOLS, TOOL_ARGUMENTS, TOOL_DESCRIPTIONS
from agents.model_gateway import ModelGateway
from agents.supervisor import LoopLimits, create_supervisor_graph
from runtime.backend_client import BackendError, MallBackendClient, valid_service_token
from runtime.checkpoints import CheckpointStore
from runtime.foundation import FoundationBackend
from runtime.service import ChatRequest, ResumeRequest, RunRequest, RunService
from rag.models import BuildRequest, SearchRequest

load_dotenv(Path(__file__).resolve().parents[1] / ".env")
logger = logging.getLogger(__name__)


def error_status(error: BackendError) -> int:
    if error.code in {"STALE_RUN", "RUN_BUSY", "INVALID_DECISION", "INVALID_CANCEL"}:
        return 409
    if error.code in {"IDENTITY_MISMATCH", "ACCESS_DENIED", "RUN_INACTIVE"}:
        return 403
    if error.code == "RUN_NOT_FOUND":
        return 404
    if error.code == "UNSAFE_REPLY":
        return 422
    return 503


def create_app(*, runtime: RunService | None = None, service_token: str | None = None) -> FastAPI:
    expected_token = service_token if service_token is not None else os.getenv("AGENT_SERVICE_TOKEN", "")

    async def authorize(x_agent_service_token: str | None = Header(default=None)) -> None:
        if not expected_token:
            raise HTTPException(status_code=503, detail="未配置服务间访问凭证")
        if not valid_service_token(x_agent_service_token, expected_token):
            raise HTTPException(status_code=401, detail="服务身份校验失败")

    @asynccontextmanager
    async def lifespan(application: FastAPI):
        backend = None
        checkpoints = None
        knowledge = None
        if runtime is None:
            from langchain_openai import ChatOpenAI
            if not expected_token:
                raise RuntimeError("必须配置 AGENT_SERVICE_TOKEN")
            model_name = os.getenv("MODEL_NAME")
            if not model_name:
                raise RuntimeError("必须配置 MODEL_NAME")
            if not os.getenv("OPENAI_API_KEY", "").strip():
                raise RuntimeError("请在 mall-customer/.env 的 OPENAI_API_KEY 中填写模型服务的 API Key")
            mode = os.getenv("AGENT_RUNTIME_MODE", "java")
            if mode not in {"foundation", "java"}:
                raise RuntimeError("AGENT_RUNTIME_MODE 仅支持 foundation 或 java")
            # PostgreSQL 保存执行现场，初始化失败时直接停止启动。
            checkpoints = CheckpointStore()
            saver = await checkpoints.setup()
            try:
                backend = (MallBackendClient(os.getenv("MALL_BACKEND_URL", "http://localhost:8800"), expected_token)
                           if mode == "java" else FoundationBackend())
                model = ChatOpenAI(model=model_name, api_key=os.getenv("OPENAI_API_KEY"),
                                   base_url=os.getenv("OPENAI_BASE_URL"), temperature=0,
                                   timeout=float(os.getenv("MODEL_TIMEOUT_SECONDS", "60")), max_retries=1)
                gateway = ModelGateway(model, timeout=float(os.getenv("MODEL_TIMEOUT_SECONDS", "60")))
                if os.getenv("RAG_ENABLED", "false").lower() == "true":
                    from rag.config import RAGConfig
                    from rag.service import KnowledgeService
                    knowledge = await KnowledgeService(RAGConfig.from_env()).setup()
                    application.state.knowledge = knowledge
                limits = LoopLimits(active_seconds=float(os.getenv("AGENT_ACTIVE_TIMEOUT_SECONDS", "180")))
                graph = create_supervisor_graph(model=gateway, backend=backend, checkpointer=saver, limits=limits, knowledge=knowledge)
                from memory.conversation import ConversationMemory
                memory = ConversationMemory(gateway, backend) if mode == "java" and os.getenv("CONVERSATION_MEMORY_ENABLED", "true").lower() == "true" else None
                application.state.runtime = RunService(graph, backend, memory=memory)
            except BaseException:
                if knowledge is not None:
                    await knowledge.close()
                if backend is not None:
                    await backend.close()
                await checkpoints.close()
                raise
        try:
            yield
        finally:
            jobs = list(application.state.jobs)
            if jobs:
                _, pending = await asyncio.wait(jobs, timeout=10)
                for task in pending:
                    task.cancel()
                await asyncio.gather(*jobs, return_exceptions=True)
            if backend is not None:
                await backend.close()
            if knowledge is not None:
                await knowledge.close()
            if checkpoints is not None:
                await checkpoints.close()

    application = FastAPI(title="商城客服 Agent 运行服务",
                          version="2.0.0",
                          lifespan=lifespan,
                          dependencies=[Depends(authorize)],
                          docs_url=None,
                          redoc_url=None,
                          openapi_url=None)
    application.state.runtime = runtime
    application.state.jobs = set()
    application.state.knowledge = None

    def knowledge_for(request: Request):
        value = request.app.state.knowledge
        if value is None:
            raise HTTPException(status_code=503, detail="知识库未启用，请配置 RAG_ENABLED 及外部服务")
        return value

    @application.post("/api/knowledge/build")
    async def build_knowledge(body: BuildRequest, request: Request):
        service = knowledge_for(request)
        try:
            return await service.build(body.source)
        except ValueError:
            raise HTTPException(status_code=422, detail="知识库构建参数无效、文件不可用或正在构建") from None
        except Exception:
            raise HTTPException(status_code=503, detail="知识库构建失败，已生效版本保持可用，请检查解析和存储服务") from None

    @application.post("/api/knowledge/search")
    async def search_knowledge(body: SearchRequest, request: Request):
        service = knowledge_for(request)
        try:
            return await service.search(body.query,body.k)
        except Exception:
            raise HTTPException(status_code=503, detail="知识库检索服务暂时不可用") from None

    def runtime_for(request: Request) -> RunService:
        value = request.app.state.runtime
        if value is None:
            raise HTTPException(status_code=503, detail="运行服务尚未初始化")
        return value

    @application.exception_handler(BackendError)
    async def backend_error(request: Request, error: BackendError):
        return JSONResponse(status_code=error_status(error), content={"code": error.code, "message": error.public_message})

    @application.exception_handler(RequestValidationError)
    async def validation_error(request: Request, error: RequestValidationError):
        # 不把敏感字段的原始 input 回显给调用方或日志。
        details = [{"loc": item["loc"], "type": item["type"], "msg": item["msg"]} for item in error.errors()]
        return JSONResponse(status_code=422, content={"detail": details})

    #ifespan 启动时需要连接数据库、初始化 checkpointer、构建 graph 等——这中间可能有几秒窗口。
    #此时如果流量进来就会 503，所以健康检查能让编排器等就绪了再放量。
    @application.get("/health")
    async def health(request: Request):
        return {"status": "ready" if request.app.state.runtime is not None else "initializing", "version": "2.0.0"}

    @application.get("/api/tools")
    async def tools(request: Request):
        if not runtime_for(request).backend.business_enabled:
            if request.app.state.knowledge is not None:
                from agents.business_tools import BusinessTools
                tools = BusinessTools(runtime_for(request).backend,request.app.state.knowledge)
                return {"agents":{agent:tools.descriptions(agent) for agent in AGENT_TOOLS},"status":"ready"}
            return {"agents": {agent: [] for agent in AGENT_TOOLS},
                    "status": "disabled", "message": "基础模式未启用 Java 业务工具；知识检索需配置 RAG_ENABLED 和外部服务"}
        return {"agents": {agent: [{"name": name, "description": TOOL_DESCRIPTIONS[name],
                                    "parameters": TOOL_ARGUMENTS[name].model_json_schema()} for name in sorted(names)]
                           for agent, names in AGENT_TOOLS.items()}}

    #TODO 也可在这里进行鉴权
    @application.post("/api/chat")
    async def chat(body: ChatRequest, request: Request):
        # 找到应用启动时创建的 runtime 实例，调用其 chat 方法处理请求。
        service = runtime_for(request)
        # 与 SSE 连接寿命分开：客户端离开不撤销已接受的业务运行和结果落库。
        # 把 service.start(body) 交给后台任务去跑，返回给客户端一个 task对象。
        task = asyncio.create_task(service.start(body))
        application.state.jobs.add(task)

        def finished(job):
            application.state.jobs.discard(job)
            if not job.cancelled() and job.exception() is not None:
                logger.error("Agent 运行异常，运行编号=%s，类型=%s", body.run_id, type(job.exception()).__name__)

        task.add_done_callback(finished)  #回调函数

        async def events():
            def event(payload: dict[str, Any]) -> str:
                return "data: " + json.dumps(payload, ensure_ascii=False) + "\n\n"
            yield event({"type": "status", "run_id": body.run_id, "status": "running", "content": "正在处理您的请求"})
            try:
                # Agent 完成后取得处理结果 等待返回结果
                outcome = await asyncio.shield(task)
                status = outcome["status"]
                base = {key: outcome[key] for key in ("run_id", "status", "message_id", "human_task_id", "revision", "reason", "actions") if outcome.get(key) is not None}
                if status == "waiting_human":
                    yield event({"type": "interrupted", **base, "content": outcome.get("reply") or "已提交人工处理"})
                elif status == "failed":
                    yield event({"type": "error", **base, "content": outcome.get("reply") or "自动处理未能完成"})
                elif outcome.get("reply"):
                    yield event({"type": "message", **base, "content": outcome["reply"]})
                yield event({"type": "done", "run_id": body.run_id, "status": status})
            except BackendError as error:
                yield event({"type": "error", "run_id": body.run_id, "status": "failed", "code": error.code, "content": error.public_message})
                yield event({"type": "done", "run_id": body.run_id, "status": "failed"})
            except Exception:
                yield event({"type": "error", "run_id": body.run_id, "status": "failed", "code": "RUNTIME_UNAVAILABLE", "content": "处理暂时中断，执行现场已保留，请稍后重试。"})
                yield event({"type": "done", "run_id": body.run_id, "status": "failed"})

        return StreamingResponse(events(), media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    @application.post("/api/runs/{run_id}/resume")
    async def resume(run_id: str, body: ResumeRequest, request: Request):
        if body.run_id != run_id:
            raise HTTPException(status_code=409, detail="运行编号不一致")
        return await runtime_for(request).resume(body)

    @application.post("/api/runs/{run_id}/cancel")
    async def cancel(run_id: str, body: RunRequest, request: Request):
        if body.run_id != run_id:
            raise HTTPException(status_code=409, detail="运行编号不一致")
        return await runtime_for(request).cancel(body)

    @application.get("/api/runs/{run_id}")
    @application.get("/api/runs/{run_id}/status")
    async def status(run_id: str, session_id: str, request: Request):
        return await runtime_for(request).status(run_id, session_id)

    return application


app = create_app()

if __name__ == "__main__":
    import uvicorn
    config = uvicorn.Config("api.main:app", host=os.getenv("HOST", "0.0.0.0"), port=int(os.getenv("PORT", "8000")))
    if os.name == "nt":
        # 新版 Uvicorn 自己选择 loop_factory，会绕过全局 policy；显式创建兼容 Psycopg 的循环。
        with asyncio.Runner(loop_factory=asyncio.SelectorEventLoop) as runner:
            runner.run(uvicorn.Server(config).serve())
    else:
        uvicorn.Server(config).run()
