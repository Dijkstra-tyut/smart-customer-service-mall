import asyncio
import json
from collections import defaultdict, deque
from contextlib import asynccontextmanager

import httpx
import pytest

from agents.business_tools import BusinessTools
from agents.contracts import ReviewVerdict
from agents.supervisor import LoopLimits, create_supervisor_graph, text_violations
from api.main import create_app
from runtime.backend_client import BackendError, MallBackendClient
from runtime.checkpoints import CheckpointStore
from runtime.context import RunContext, bind_context, current_context
from runtime.service import ChatRequest, ResumeRequest, RunService, config_for


class ScriptedModel:
    def __init__(self, **scripts):
        self.scripts = {phase: deque(values) for phase, values in scripts.items()}
        self.calls = []

    async def structured(self, schema, phase, payload):
        self.calls.append((phase, payload))
        value = self.scripts[phase].popleft()
        if isinstance(value, Exception):
            raise value
        return schema.model_validate(value)

    async def text(self, phase, payload):
        self.calls.append((phase, payload))
        value = self.scripts[phase].popleft()
        if isinstance(value, Exception):
            raise value
        return value


class MockJava:
    def __init__(self):
        self.runs = {}
        self.tasks = {}
        self.outcomes = {}
        self.actions = {}
        self.calls = []
        self.publish_failures = 0

    def register(self, run_id='r1', session_id='s1', user_id='u1', revision=1, token=None):
        token = token or f'run-secret-{run_id}-123456789'
        self.runs[token] = dict(run_id=run_id, session_id=session_id, user_id=user_id,
                                revision=revision, status='running', user_context={'display_name': '顾客'})
        return token

    def body(self, run_id='r1', session_id='s1', user_id='u1', revision=1, message='你好', token=None):
        token = self.register(run_id, session_id, user_id, revision, token)
        return dict(run_id=run_id, session_id=session_id, user_id=user_id, revision=revision,
                    run_token=token, request_id=f'req-{run_id}-{revision}',
                    user_context={'display_name': '顾客'}, history=[], message=message,
                    message_id=f'input-{run_id}-{revision}')

    async def handle(self, request):
        assert request.headers['X-Agent-Service-Token'] == 'service-test-token'
        token = request.headers['X-Agent-Run-Token']
        run = self.runs[token]
        body = json.loads(request.content) if request.content else None
        path = request.url.path
        self.calls.append((request.method, path, body, run['user_id']))
        if path.endswith('/context'):
            data = run.copy()
        elif path.endswith('/orders/99'):
            return httpx.Response(403, json={'code': 403})
        elif '/orders/' in path:
            data = {'order_id': int(path.rsplit('/', 1)[1]), 'owner': run['user_id'], 'status': 'pending_pay'}
        elif '/products/' in path:
            data = {'product_id': 1, 'name': '测试商品', 'price': 10}
        elif path.endswith('/human-tasks'):
            existing = self.tasks.get(body['task_id'])
            if existing:
                assert existing['payload'] == body
            else:
                self.tasks[body['task_id']] = {'payload': body, 'status': 'preparing', 'version': 1}
            data = {'task_id': body['task_id'], 'version': 1}
        elif path.endswith('/activate'):
            task_id = path.split('/')[-2]
            self.tasks[task_id]['status'] = 'pending'
            run['status'] = 'waiting_human'
            data = {'task_id': task_id, 'version': 1}
        elif path.endswith('/actions'):
            if body['kind'] != 'ticket_create':
                assert body['approval_task_id'] in self.tasks
            self.actions.setdefault(body['operation_id'], body)
            data = {'status': 'completed', 'operation_id': body['operation_id'], 'kind': body['kind']}
        elif path.endswith('/outcome'):
            if self.publish_failures:
                self.publish_failures -= 1
                return httpx.Response(503, json={'code': 503})
            self.outcomes.setdefault(body['event_id'], body)
            run['status'] = body['status']
            data = {key: body.get(key) for key in ('run_id', 'status', 'reply', 'message_id', 'human_task_id')}
            data['revision'] = body['expected_revision']
        else:
            data = {'items': [], 'total': 0, 'page': 1, 'size': 10}
        return httpx.Response(200, json={'code': 200, 'message': '成功', 'data': data})


@asynccontextmanager
async def runtime_at(path, java, model, limits=None):
    store = CheckpointStore(sqlite_path=str(path))
    saver = await store.setup()
    backend = MallBackendClient('http://mall', 'service-test-token', transport=httpx.MockTransport(java.handle))
    service = RunService(create_supervisor_graph(model=model, backend=backend, checkpointer=saver, limits=limits), backend)
    try:
        yield service
    finally:
        await backend.close()
        await store.close()


def resume_body(chat_body, task_id, action='send_reply', reply='已为您记录并核实，请查看处理说明。', decision_id='d1', version=1):
    body = {key: value for key, value in chat_body.items() if key not in {'message', 'message_id'}}
    body['decision'] = dict(decision_id=decision_id, task_id=task_id, version=version, action=action, reply=reply, comment='人工已核对')
    return ResumeRequest.model_validate(body)


@pytest.mark.asyncio
@pytest.mark.parametrize('supplement_java_history', [False, True])
async def test_java_mode_restores_checkpoint_dialogue_after_restart(tmp_path, supplement_java_history):
    java = MockJava()
    path = tmp_path / 'dialogue.sqlite'
    first = ChatRequest.model_validate(java.body(message='你好，我是小林'))
    model = ScriptedModel(supervisor=[{'action': 'compose'}], compose=['小林你好。'], review=[{'approved': True}])
    async with runtime_at(path, java, model) as service:
        reply = await service.start(first)

    second_body = java.body(run_id='r2', message='接着刚才聊')
    if supplement_java_history:
        second_body['history'] = [
            {'id': first.message_id, 'role': 'user', 'content': first.message},
            {'id': reply['message_id'], 'database_id': '101', 'role': 'assistant', 'content': reply['reply']},
            {'id': '102', 'role': 'assistant', 'content': '人工客服补充：请保留包装。'},
        ]
    model = ScriptedModel(supervisor=[{'action': 'compose'}], compose=['好的，继续。'], review=[{'approved': True}])
    async with runtime_at(path, java, model) as service:
        await service.start(ChatRequest.model_validate(second_body))
    messages = next(payload['messages'] for phase, payload in model.calls if phase == 'supervisor')
    assert [m['content'] for m in messages].count('小林你好。') == 1
    assert messages[0]['content'] == first.message
    assert messages[-1]['content'] == '接着刚才聊'
    if supplement_java_history:
        assert messages[-2]['content'] == '人工客服补充：请保留包装。'


@pytest.mark.asyncio
@pytest.mark.parametrize('status', [401, 403])
async def test_model_authentication_failure_is_published_as_terminal_failure(tmp_path, status):
    from openai import AuthenticationError, PermissionDeniedError
    from agents.model_gateway import ModelGateway
    class DeniedModel:
        async def ainvoke(self, messages):
            error = AuthenticationError if status == 401 else PermissionDeniedError
            raise error('private-provider-details', response=httpx.Response(status, request=httpx.Request('POST', 'https://model.test')), body=None)
    java = MockJava()
    request = ChatRequest.model_validate(java.body())
    async with runtime_at(tmp_path / 'denied.sqlite', java, ModelGateway(DeniedModel())) as service:
        result = await service.start(request)
        assert result['status'] == 'failed'
        state = (await service.graph.aget_state(config_for(request.session_id))).values
        assert state['error_code'] == 'MODEL_AUTHENTICATION_FAILED'
        assert next(iter(java.outcomes.values()))['status'] == 'failed'
        assert 'private-provider-details' not in json.dumps(state)


@pytest.mark.asyncio
async def test_context_isolated_and_restored():
    java = MockJava()
    a, b = java.body(user_id='a'), java.body(run_id='r2', session_id='s2', user_id='b')
    client = MallBackendClient('http://mall', 'service-test-token', transport=httpx.MockTransport(java.handle))
    async def read(body):
        request = ChatRequest.model_validate(body)
        with bind_context(request.context()):
            await asyncio.sleep(0)
            return await BusinessTools(client).call('service', 'get_order', {'order_id': 1})
    try:
        results = await asyncio.gather(read(a), read(b))
        assert [result['data']['owner'] for result in results] == ['a', 'b']
        with pytest.raises(RuntimeError, match='上下文'):
            current_context()
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_tools_enforce_identity_parameters_and_allowlist():
    java = MockJava()
    body = java.body()
    client = MallBackendClient('http://mall', 'service-test-token', transport=httpx.MockTransport(java.handle))
    try:
        with bind_context(ChatRequest.model_validate(body).context()):
            tools = BusinessTools(client)
            assert (await tools.call('consultation', 'get_order', {'order_id': 1}))['code'] == 'TOOL_FORBIDDEN'
            assert (await tools.call('service', 'get_order', {'order_id': 1, 'user_id': 'other'}))['code'] == 'INVALID_ARGUMENTS'
            assert (await tools.call('consultation', 'search_knowledge', {'query': '退款规则'}))['code'] == 'KNOWLEDGE_UNAVAILABLE'
            with pytest.raises(BackendError, match='无权'):
                await tools.call('service', 'get_order', {'order_id': 99})
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_supervisor_coordinates_two_specialists_and_saves_evidence(tmp_path):
    java = MockJava()
    body = java.body(message='查一下商品和我的订单')
    model = ScriptedModel(
        supervisor=[{'action': 'delegate', 'agent': 'consultation', 'goal': '查商品'},
                    {'action': 'delegate', 'agent': 'service', 'goal': '查订单'}, {'action': 'compose'}],
        consultation=[{'action': 'call_tool', 'tool': 'get_product', 'arguments': {'product_id': 1}},
                      {'action': 'finish', 'summary': '找到商品', 'evidence_ids': ['e-task-1-1']}],
        service=[{'action': 'call_tool', 'tool': 'get_order', 'arguments': {'order_id': 1}},
                 {'action': 'finish', 'summary': '订单待支付', 'evidence_ids': ['e-task-2-1']}],
        compose=['商品资料与订单状态已查到。'], review=[{'approved': True}],
    )
    async with runtime_at(tmp_path / 'graph.sqlite', java, model) as service:
        result = await service.start(ChatRequest.model_validate(body))
        assert result['status'] == 'completed'
        state = (await service.graph.aget_state(config_for('s1'))).values
        assert len(state['results']) == 2 and state['tool_calls'] == 2
        assert len(state['evidence']) == 2
        serialized = json.dumps(state, ensure_ascii=False)
        assert body['run_token'] not in serialized and 'service-test-token' not in serialized
        assert all(body['run_token'] not in json.dumps(payload, ensure_ascii=False) for _, payload in model.calls)


@pytest.mark.asyncio
async def test_durable_human_edit_and_duplicate_resume(tmp_path):
    java = MockJava()
    model = ScriptedModel(supervisor=[{'action':'delegate','agent':'service','goal':'取消订单'}], service=[{'action':'propose_action','proposal':{'kind':'order_cancel','arguments':{'order_id':1}}}])
    body = java.body(message='取消订单1')
    path = tmp_path / 'restart.sqlite'
    async with runtime_at(path, java, model) as service:
        initial = await service.start(ChatRequest.model_validate(body))
        task_id = initial['human_task_id']
        assert initial['status'] == 'waiting_human'
        assert (await service.status('r1', 's1'))['checkpoint_paused'] is True
        assert [phase for phase,_ in model.calls] == ['supervisor','service']
    # 新进程对象、新连接和新上下文仍能继续同一个中断。
    body['run_token'] = java.register(token='fresh-run-token-123456789')
    exact_reply = '人工已核对：您的诉求已记录。\n请保留当前会话。'
    request = resume_body(body, task_id, reply=exact_reply)
    async with runtime_at(path, java, ScriptedModel()) as service:
        result = await service.resume(request)
        repeated = await service.resume(request)
        assert result == repeated
        assert result['reply'] == exact_reply
        assert result['status'] == 'completed'
        assert (await service.status('r1', 's1'))['checkpoint_paused'] is False
        assert len(java.tasks) == 1
        assert len([outcome for outcome in java.outcomes.values() if outcome['status'] == 'completed']) == 1
        state = (await service.graph.aget_state(config_for('s1'))).values
        assert 'fresh-run-token' not in json.dumps(state)


@pytest.mark.asyncio
async def test_sensitive_action_only_executes_after_approval(tmp_path):
    java = MockJava()
    body = java.body(message='取消订单1')
    model = ScriptedModel(supervisor=[{'action': 'delegate', 'agent': 'service', 'goal': '取消订单'}, {'action': 'compose'}],
                          service=[{'action': 'propose_action', 'proposal': {'kind': 'order_cancel', 'arguments': {'order_id': 1}}}],
                          compose=['订单取消结果已确认。'], review=[{'approved': True}])
    async with runtime_at(tmp_path / 'approval.sqlite', java, model) as service:
        paused = await service.start(ChatRequest.model_validate(body))
        assert paused['reason'] == 'sensitive_action' and not java.actions
        decision = resume_body(body, paused['human_task_id'], action='approve_action', reply=None)
        java.runs[body['run_token']]['status'] = 'running'
        result = await service.resume(decision)
        assert result['status'] == 'completed' and len(java.actions) == 1
        await service.resume(decision)
        assert len(java.actions) == 1


@pytest.mark.asyncio
async def test_reject_action_never_executes_and_compose_is_terminal(tmp_path):
    java = MockJava()
    body = java.body(message='取消订单1')
    model = ScriptedModel(supervisor=[{'action': 'delegate', 'agent': 'service', 'goal': '取消订单'}],
                          service=[{'action': 'propose_action', 'proposal': {'kind': 'order_cancel', 'arguments': {'order_id': 1}}}],
                          compose=['人工未批准本次取消申请，订单未由客服取消。'], review=[{'approved': True}])
    async with runtime_at(tmp_path / 'rejected.sqlite', java, model) as service:
        paused = await service.start(ChatRequest.model_validate(body))
        result = await service.resume(resume_body(body, paused['human_task_id'], action='reject_action', reply=None))
        assert result['status'] == 'completed' and not java.actions
        state = (await service.graph.aget_state(config_for('s1'))).values
        assert state['rejected_actions'] == ['order_cancel:1']


@pytest.mark.asyncio
async def test_invalid_reviews_and_unknown_citations_are_not_approved(tmp_path):
    java = MockJava()
    body = java.body()
    model = ScriptedModel(supervisor=[{'action': 'compose'}, {'action': 'compose'}],
                          compose=['无依据的政策 [invented-source]', '缺少依据的回复'],
                          review=[ValueError('无效JSON')])
    async with runtime_at(tmp_path / 'review.sqlite', java, model) as service:
        result = await service.start(ChatRequest.model_validate(body))
        assert result['status'] == 'completed' and result['actions'] == ['handoff']
        state = (await service.graph.aget_state(config_for('s1'))).values
        assert '无效' in state['review']['issues'][0]
        assert not java.tasks
        assert len([phase for phase, _ in model.calls if phase == 'review']) == 1


@pytest.mark.asyncio
async def test_semantic_human_request_not_limited_to_keyword(tmp_path):
    java = MockJava()
    body = java.body(message='请让你们工作人员接着处理')
    model = ScriptedModel(supervisor=[{'action': 'request_human', 'reason': 'user_requested'}])
    async with runtime_at(tmp_path / 'human.sqlite', java, model) as service:
        result = await service.start(ChatRequest.model_validate(body))
        assert result['status'] == 'completed' and result['reason'] == 'user_requested'
        assert result['actions'] == ['handoff'] and not java.tasks


@pytest.mark.asyncio
async def test_publish_retry_does_not_repeat_agent_or_action(tmp_path):
    java = MockJava()
    body = java.body()
    model = ScriptedModel(supervisor=[{'action': 'compose'}], compose=['您好，请问有什么可以帮助您？'], review=[{'approved': True}])
    async with runtime_at(tmp_path / 'publish.sqlite', java, model) as service:
        java.publish_failures = 1
        with pytest.raises(BackendError):
            await service.start(ChatRequest.model_validate(body))
        calls = len(model.calls)
        result = await service.start(ChatRequest.model_validate(body))
        assert result['status'] == 'completed' and len(model.calls) == calls
        assert len(java.outcomes) == 1


@pytest.mark.asyncio
async def test_cancelled_old_interrupt_does_not_block_new_run(tmp_path):
    java = MockJava()
    first = java.body(message='取消订单1')
    model = ScriptedModel(supervisor=[{'action':'delegate','agent':'service','goal':'取消订单'},{'action': 'compose'}], service=[{'action':'propose_action','proposal':{'kind':'order_cancel','arguments':{'order_id':1}}}], compose=['您好，新诉求已收到。'], review=[{'approved': True}])
    async with runtime_at(tmp_path / 'new.sqlite', java, model) as service:
        assert (await service.start(ChatRequest.model_validate(first)))['status'] == 'waiting_human'
        java.runs[first['run_token']]['status'] = 'cancelled'
        second = java.body(run_id='r2', message='你好')
        result = await service.start(ChatRequest.model_validate(second))
        assert result['run_id'] == 'r2' and result['status'] == 'completed'


@pytest.mark.asyncio
async def test_stale_approval_rejected_but_current_manual_text_allowed(tmp_path):
    java = MockJava()
    body = java.body(message='取消订单1')
    model = ScriptedModel(supervisor=[{'action': 'delegate', 'agent': 'service', 'goal': '取消'}],
                          service=[{'action': 'propose_action', 'proposal': {'kind': 'order_cancel', 'arguments': {'order_id': 1}}}])
    async with runtime_at(tmp_path / 'stale.sqlite', java, model) as service:
        paused = await service.start(ChatRequest.model_validate(body))
        body['revision'] = 2
        java.runs[body['run_token']]['revision'] = 2
        with pytest.raises(BackendError, match='旧操作'):
            await service.resume(resume_body(body, paused['human_task_id'], action='approve_action', reply=None, version=2))
        result = await service.resume(resume_body(body, paused['human_task_id'], reply='已阅读您的补充信息，原申请未执行。', version=2))
        assert result['status'] == 'completed' and not java.actions


@pytest.mark.asyncio
async def test_request_info_continues_same_run_without_reset_budget(tmp_path):
    java = MockJava()
    body = java.body(message='取消订单1')
    model = ScriptedModel(supervisor=[{'action':'delegate','agent':'service','goal':'取消订单'},{'action': 'compose'}], service=[{'action':'propose_action','proposal':{'kind':'order_cancel','arguments':{'order_id':1}}}], compose=['订单号已收到。'], review=[{'approved': True}])
    async with runtime_at(tmp_path / 'question.sqlite', java, model) as service:
        paused = await service.start(ChatRequest.model_validate(body))
        question = await service.resume(resume_body(body, paused['human_task_id'], action='request_info', reply='请补充订单号。'))
        assert question['status'] == 'waiting_user'
        continuation = java.body(revision=2, message='订单号1')
        result = await service.start(ChatRequest.model_validate(continuation))
        assert result['status'] == 'completed' and result['run_id'] == 'r1'
        state = (await service.graph.aget_state(config_for('s1'))).values
        assert state['supervisor_steps'] == 2 and state['author_type'] == 'assistant'


@pytest.mark.asyncio
async def test_api_auth_and_only_typed_public_events(tmp_path):
    java = MockJava()
    body = java.body()
    model = ScriptedModel(supervisor=[{'action': 'compose'}], compose=['您好，请问需要什么帮助？'], review=[{'approved': True}])
    async with runtime_at(tmp_path / 'api.sqlite', java, model) as service:
        app = create_app(runtime=service, service_token='service-test-token')
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://agent') as client:
            assert (await client.post('/api/chat', json=body)).status_code == 401
            response = await client.post('/api/chat', json=body, headers={'X-Agent-Service-Token': 'service-test-token'})
            data = [json.loads(line[6:]) for line in response.text.splitlines() if line.startswith('data: ')]
            assert [event['type'] for event in data] == ['status', 'message', 'done']
            assert body['run_token'] not in response.text
            status = await client.get('/api/runs/r1/status?session_id=s1', headers={'X-Agent-Service-Token': 'service-test-token'})
            assert status.json()['checkpoint_paused'] is False
            assert (await client.post('/api/tools/call', json={}, headers={'X-Agent-Service-Token': 'service-test-token'})).status_code == 404


def test_human_text_rules_and_empty_service_auth_fail_closed():
    assert text_violations('邮箱 a@example.com')
    assert text_violations('手机 13800138000')
    with pytest.raises(ValueError):
        MallBackendClient('http://mall', '')

@pytest.mark.asyncio
async def test_global_active_time_budget_limits_slow_model(tmp_path):
    java = MockJava()
    body = java.body()
    class SlowModel:
        async def structured(self, schema, phase, payload):
            await asyncio.sleep(0.2)
            return schema.model_validate({'action': 'compose'})
    async with runtime_at(tmp_path / 'time.sqlite', java, SlowModel(), LoopLimits(active_seconds=0.03)) as service:
        result = await service.start(ChatRequest.model_validate(body))
        assert result['status'] == 'failed' and not java.actions
        state = (await service.graph.aget_state(config_for('s1'))).values
        assert state['error_code'] == 'ACTIVE_TIME_LIMIT'


@pytest.mark.asyncio
async def test_before_interrupt_checkpoint_is_not_reported_as_paused(tmp_path):
    java = MockJava()
    body = java.body(message='取消订单1')
    model = ScriptedModel(supervisor=[{'action':'delegate','agent':'service','goal':'取消订单'}], service=[{'action':'propose_action','proposal':{'kind':'order_cancel','arguments':{'order_id':1}}}])
    async with runtime_at(tmp_path / 'prepared.sqlite', java, model) as service:
        request = ChatRequest.model_validate(body)
        initial = dict(run_id='r1', session_id='s1', user_id='u1', revision=1,
                       messages=[{'id': body['message_id'], 'role': 'user', 'content': body['message']}],
                       user_context={}, status='running', next='supervisor')
        # 模拟进程在 prepare_human 已提交、但 human_wait 尚未执行的节点边界退出。
        with bind_context(request.context()):
            await service.graph.ainvoke(initial, config_for('s1'), interrupt_before=['human_wait'])
        status = await service.status('r1', 's1')
        assert status['checkpoint_paused'] is False
        assert next(iter(java.tasks.values()))['status'] == 'preparing'
        result = await service.start(request)
        assert result['status'] == 'waiting_human'
        assert (await service.status('r1', 's1'))['checkpoint_paused'] is True
        assert next(iter(java.tasks.values()))['status'] == 'pending'
