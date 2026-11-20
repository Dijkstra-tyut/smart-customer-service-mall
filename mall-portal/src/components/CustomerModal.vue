<template>
  <transition name="cs-panel">
    <div v-show="visible" class="cs-panel">
      <!-- 头部：头像 + 称呼 + 操作 -->
      <div class="hd">
        <div class="face">商</div>
        <div class="hd-text">
          <div class="name">小店助手</div>
          <div class="stat">智能客服 · 为你解答购物问题</div>
        </div>
        <div class="acts">
          <button type="button" title="历史会话" aria-label="查看历史会话" @click="drawerOpen = true">☰</button>
          <button type="button" title="收起" aria-label="收起智能客服" @click="visible = false">
            <el-icon :size="15"><Minus /></el-icon>
          </button>
        </div>
      </div>

      <!-- 消息区 -->
      <div class="msgs" ref="msgListRef" role="log" aria-label="客服会话">
        <div v-if="messages.length === 0" class="welcome">
          <div class="hi">你好呀 <em>:)</em></div>
          <p>订单、物流、退换货，都可以直接问我</p>
          <div class="q">
            <button type="button" v-for="chip in quickChips" :key="chip" @click="quickAsk(chip)">{{ chip }}</button>
          </div>
        </div>

        <div
          v-for="(msg, index) in messages"
          :key="msg.id || index"
          :class="['row', msg.type === 'user' ? 'u' : 'b']"
        >
          <div v-if="msg.type !== 'user'" class="av">{{ msg.origin === 'human' ? '客服' : msg.origin === 'system' ? '系统' : 'AI' }}</div>
          <div class="bb">{{ msg.content }}</div>
        </div>

        <!-- 流式输出中的回复 -->
        <div v-if="loading && currentStreamingText" class="row b">
          <div class="av">AI</div>
          <div class="bb streaming">{{ currentStreamingText }}</div>
        </div>
        <!-- 思考中 -->
        <div v-else-if="loading" class="row b">
          <div class="av">AI</div>
          <div class="bb">
            <span class="dots" aria-hidden="true"><span></span><span></span><span></span></span>
            <span class="thinking-text">{{ progressText || '正在处理…' }}</span>
          </div>
        </div>
      </div>

      <div v-if="isLiveActive" class="run-notice" role="status">
        <p>{{ liveSession.status === 'waiting' ? '已进入人工队列，可以先补充问题。' : '人工客服正在为你服务。' }}</p>
        <small>{{ liveConnection === 'connected' ? '实时连接正常' : '正在重连，消息记录会自动补齐…' }}</small>
        <div class="notice-actions"><button :disabled="actionBusy" @click="endLive">结束人工服务，返回智能客服</button></div>
      </div>
      <div v-else class="customer-actions">
        <span v-if="run?.actions?.length" class="action-hint">根据你的问题，可以选择：</span>
        <button v-for="action in availableActions" :key="action" type="button" :disabled="actionBusy||loading||refreshing" :class="{suggested:run?.actions?.includes(action)}" @click="performAction(action)">{{ actionLabels[action] }}</button>
      </div>

      <div v-if="statusText || deliveryError" class="run-notice" role="status" aria-live="polite">
        <p v-if="statusText">{{ statusText }}</p>
        <p v-if="run?.status === 'waiting_human'" class="notice-help">你可以继续补充信息。处理结果会保留在这个会话中。</p>
        <p v-if="deliveryError" class="notice-error">{{ deliveryError }}</p>
        <div class="notice-actions">
          <button type="button" :disabled="refreshing || loading" @click="refreshCurrentSession">{{ refreshing ? '正在刷新…' : '刷新进度' }}</button>
          <button v-if="canCancel" type="button" :disabled="cancelling" @click="cancelRun">{{ cancelling ? '正在取消…' : '取消本次处理' }}</button>
        </div>
      </div>

      <!-- 输入区：药丸框 -->
      <div class="ft">
        <div class="pill">
          <textarea
            v-model="inputMessage"
            :placeholder="isLiveActive ? '发送给人工客服…' : run?.status === 'waiting_human' ? '补充要告诉人工客服的信息…' : '发消息…'"
            aria-label="发送给客服的消息"
            :disabled="!canSend"
            @keydown.enter.exact.prevent="sendMessage"
          ></textarea>
          <button
            class="send"
            title="发送"
            :disabled="!inputMessage.trim() || !canSend"
            @click="sendMessage"
          >
            <el-icon :size="15"><Promotion /></el-icon>
          </button>
        </div>
      </div>

      <!-- 历史会话抽屉（底部上滑） -->
      <div class="drawer" :class="{ open: drawerOpen }" @click.self="drawerOpen = false">
        <div class="sheet">
          <div class="grab"></div>
          <div class="sh">
            <h3>历史会话</h3>
            <button class="new" @click="handleCreateSession">＋ 新会话</button>
          </div>
          <div class="list">
            <div
              v-for="s in sessions"
              :key="s.id"
              :class="['sh-item', { cur: currentSessionId === s.id }]"
              @click="selectSession(s.id)"
            >
              <div class="ci"><el-icon :size="15"><ChatDotRound /></el-icon></div>
              <div class="ti" :title="s.title">{{ s.title }}</div>
              <button type="button" class="del" :aria-label="`删除会话 ${s.title}`" @click.stop="handleDeleteSession(s.id)">×</button>
            </div>
            <div v-if="sessions.length === 0" class="empty">暂无历史对话</div>
          </div>
        </div>
      </div>
    </div>
  </transition>
  <el-dialog v-model="ordersVisible" :title="afterSalesMode?'提交售后申请':'我的订单'" width="min(700px, 94vw)" append-to-body>
    <p v-if="afterSalesMode">选择已支付、已发货或已完成的订单，填写原因后确认提交。申请成功后等待核实。</p>
    <el-table :data="orders" highlight-current-row @current-change="selectedOrder=$event">
      <el-table-column prop="orderSn" label="订单号" min-width="160" />
      <el-table-column label="状态"><template #default="scope">{{ orderStatusLabel(scope.row.status) }}</template></el-table-column>
      <el-table-column prop="totalAmount" label="金额" />
    </el-table>
    <template v-if="afterSalesMode"><el-input v-model="afterReason" type="textarea" :rows="3" maxlength="2000" aria-label="售后申请原因" placeholder="请说明需要协助的具体问题" /><p>此操作提交售后工单，退货和退款需要后续核实。</p></template>
    <template #footer><el-button @click="ordersVisible=false">关闭</el-button><el-button v-if="!afterSalesMode" @click="afterSalesMode=true">申请售后</el-button><el-button v-else type="primary" :loading="actionBusy" :disabled="!eligibleOrder||!afterReason.trim()" @click="confirmAfterSales">确认提交售后申请</el-button></template>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { Minus, Promotion, ChatDotRound } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelCustomerRun, createCustomerSession, deleteCustomerSession, getCustomerRun, listCustomerMessages, listCustomerSessions, getCustomerLive, handoffCustomer, submitAfterSales, closeCustomerLive } from '@/api/customer'
import { getOrderList } from '@/api/order'
import { connectLive } from '../../../shared/live-client.js'
import { createCustomerEventParser, customerStatusText } from '@/utils/customer-events'

const visible = ref(false)
const drawerOpen = ref(false)
const sessions = ref([])
const currentSessionId = ref(null)
const messages = ref([])
const inputMessage = ref('')
const loading = ref(false)
const cancelling = ref(false)
const currentStreamingText = ref('')
const msgListRef = ref(null)
const run = ref(null)
const progressText = ref('')
const deliveryError = ref('')
const refreshing = ref(false)
const liveSession=ref(null), liveConnection=ref('connecting'), actionBusy=ref(false)
const ordersVisible=ref(false), afterSalesMode=ref(false), orders=ref([]), selectedOrder=ref(null), afterReason=ref('')
const isLiveActive=computed(()=>['waiting','serving'].includes(liveSession.value?.status))
const actionLabels={handoff:'转人工',view_orders:'查看订单',request_after_sales:'提交售后申请'}
const availableActions=computed(()=>[...new Set([...(run.value?.actions||[]).filter(action=>actionLabels[action]),'handoff','view_orders','request_after_sales'])])
const eligibleOrder=computed(()=>selectedOrder.value&&[1,2,3].includes(Number(selectedOrder.value.status)))
const orderStatusLabel=value=>({0:'待支付',1:'已支付',2:'已发货',3:'已完成',4:'已取消'})[value]||'未知'
const quickChips = ['查订单', '问物流', '退换货', '人工客服']
const activeStatuses = new Set(['running', 'waiting_human', 'waiting_user'])
const canSend = computed(() => !loading.value && !refreshing.value && !!currentSessionId.value && (isLiveActive.value ? liveConnection.value==='connected' : run.value?.status !== 'running'))
const statusText = computed(() => run.value?.status === 'running' ? progressText.value || customerStatusText('running') : customerStatusText(run.value?.status, run.value?.reason))
const canCancel = computed(() => activeStatuses.has(run.value?.status) || loading.value)
let sessionEpoch = 0
let openSequence = 0
let streamAbort = null
let pollTimer = null
let shouldPoll = false
let pollFailures = 0
let liveClient=null, liveSocketId=null, pendingLive=null, pendingAfter=null, snapshotSequence=0

const errorText = (error, fallback) => typeof error === 'string' ? error : error?.message || fallback
const isCurrent = (sessionId, epoch) => visible.value && String(currentSessionId.value) === String(sessionId) && sessionEpoch === epoch

function stopActivity() {
  ++sessionEpoch
  clearTimeout(pollTimer)
  streamAbort?.abort()
  streamAbort = null
  loading.value = false
  refreshing.value = false
  currentStreamingText.value = ''
  shouldPoll = false
  liveClient?.close();liveClient=null;liveSocketId=null;liveSession.value=null;pendingLive=null
}

async function open() {
  visible.value = true
  if (loading.value) return
  const sequence = ++openSequence
  try {
    const response = await listCustomerSessions()
    if (!visible.value || sequence !== openSequence) return
    sessions.value = response.data || []
    if (!sessions.value.length) await handleCreateSession()
    else await selectSession(sessions.value.some(item => item.id === currentSessionId.value) ? currentSessionId.value : sessions.value[0].id)
  } catch (error) { if (sequence === openSequence && visible.value) deliveryError.value = errorText(error, '加载会话失败，请重新打开客服') }
}

async function refreshSnapshot(sessionId, epoch, quiet = false) {
  if (!isCurrent(sessionId, epoch)) return false
  const sequence=++snapshotSequence
  const results = await Promise.allSettled([listCustomerMessages(sessionId), getCustomerRun(sessionId), getCustomerLive(sessionId)])
  if (!isCurrent(sessionId, epoch)||sequence!==snapshotSequence) return false
  const [historyResult, runResult, liveResult] = results
  if (historyResult.status === 'fulfilled') {
    messages.value = historyResult.value.data || []
    scrollToBottom()
  }
  if (runResult.status === 'fulfilled') {
    run.value = runResult.value.data || null
    shouldPoll = ['running', 'waiting_human'].includes(run.value?.status)
  }
  if(liveResult.status==='fulfilled'){
    liveSession.value=liveResult.value.data||null
    if(isLiveActive.value){shouldPoll=false;ensureLiveSocket(sessionId,epoch)}
    else if(liveClient){liveClient.close();liveClient=null;liveSocketId=null}
  }
  const failure = results.find(result => result.status === 'rejected')
  if (failure) {
    pollFailures++
    if (!quiet || pollFailures >= 3) deliveryError.value = '暂时无法同步最新进度，请稍后刷新。'
  } else {
    pollFailures = 0
    deliveryError.value = ''
  }
  return !failure
}

function schedulePoll(sessionId, epoch) {
  clearTimeout(pollTimer)
  if (!isCurrent(sessionId, epoch) || !shouldPoll || loading.value) return
  pollTimer = setTimeout(async () => {
    await refreshSnapshot(sessionId, epoch, true)
    schedulePoll(sessionId, epoch)
  }, Math.min(2500 * (pollFailures + 1), 15000))
}

async function selectSession(sessionId) {
  stopActivity()
  currentSessionId.value = sessionId
  inputMessage.value = ''
  messages.value = []
  run.value = null
  progressText.value = ''
  deliveryError.value = ''
  pollFailures = 0
  drawerOpen.value = false
  const epoch = sessionEpoch
  refreshing.value = true
  try { await refreshSnapshot(sessionId, epoch) }
  finally { if (isCurrent(sessionId, epoch)) { refreshing.value = false; schedulePoll(sessionId, epoch) } }
}

async function refreshCurrentSession() {
  if (!currentSessionId.value || refreshing.value || loading.value) return
  const sessionId = currentSessionId.value
  const epoch = sessionEpoch
  refreshing.value = true
  try { await refreshSnapshot(sessionId, epoch) }
  finally { if (isCurrent(sessionId, epoch)) { refreshing.value = false; schedulePoll(sessionId, epoch) } }
}

async function handleCreateSession() {
  try {
    const response = await createCustomerSession()
    if (!visible.value) return
    const newSession = response.data
    sessions.value.unshift(newSession)
    await selectSession(newSession.id)
  } catch (error) { ElMessage.error(errorText(error, '创建会话失败')) }
}

async function handleDeleteSession(sessionId) {
  try {
    await ElMessageBox.confirm('确定要删除该会话记录吗？', '删除会话', { confirmButtonText: '确定删除', cancelButtonText: '返回', type: 'warning' })
    await deleteCustomerSession(sessionId)
    sessions.value = sessions.value.filter(item => item.id !== sessionId)
    if (currentSessionId.value === sessionId) {
      stopActivity()
      currentSessionId.value = null
      messages.value = []
      if (sessions.value.length) await selectSession(sessions.value[0].id)
      else if (visible.value) await handleCreateSession()
    }
    ElMessage.success('会话已删除')
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(errorText(error, '删除会话失败')) }
}

function scrollToBottom() {
  nextTick(() => { if (msgListRef.value) msgListRef.value.scrollTop = msgListRef.value.scrollHeight })
}

async function quickAsk(text) {
  if(text==='人工客服')return performAction('handoff')
  if(text==='查订单'||text==='问物流')return performAction('view_orders')
  if(text==='退换货')return performAction('request_after_sales')
  if (loading.value || run.value?.status === 'running') return
  if (!currentSessionId.value) await handleCreateSession()
  if (!currentSessionId.value) return
  inputMessage.value = text
  await sendMessage()
}

function addPublishedMessage(event) {
  if (typeof event.content !== 'string' || !event.content) return
  const key = event.message_id
  const existing = key && messages.value.find(message => String(message.messageKey || message.message_key || message.id) === String(key))
  if (existing) existing.content = event.content
  else messages.value.push({ id: key ? `event:${key}` : `event:${Date.now()}`, messageKey: key, type: 'assistant', origin: event.author_type === 'human' ? 'human' : 'ai', content: event.content })
  scrollToBottom()
}

async function sendMessage() {
  const text = inputMessage.value.trim()
  if (!text || !canSend.value) return
  if(isLiveActive.value)return sendLiveMessage(text)
  const sessionId = currentSessionId.value
  const supplement = run.value?.status === 'waiting_human'
  clearTimeout(pollTimer)
  const epoch = ++sessionEpoch
  streamAbort?.abort()
  const controller = new AbortController()
  streamAbort = controller
  inputMessage.value = ''
  deliveryError.value = ''
  currentStreamingText.value = ''
  progressText.value = supplement ? '正在提交补充信息…' : '正在理解你的问题…'
  if (!supplement) run.value = { ...run.value, status: 'running' }
  messages.value.push({ id: `local:${epoch}`, type: 'user', content: text })
  loading.value = true
  shouldPoll = true
  scrollToBottom()
  let receivedDone = false
  let receivedMessage = false
  let receivedInterrupt = false
  let streamFailure = ''
  try {
    const response = await fetch('/portal-api/customer/chat', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, credentials: 'include', signal: controller.signal,
      body: JSON.stringify({ sessionId, message: text })
    })
    if (!isCurrent(sessionId, epoch)) return
    if (!response.ok) throw new Error('消息提交未完成，请刷新会话核对后再试。')
    if (!response.headers.get('content-type')?.includes('text/event-stream')) {
      // Application errors may use a JSON envelope even when HTTP status is 200.
      const failure = await response.json().catch(() => null)
      throw new Error(failure?.message || '客服暂时无法处理，请稍后刷新会话。')
    }
    if (!response.body) throw new Error('客服连接暂不可用，请刷新查看进度。')
    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    const parser = createCustomerEventParser(event => {
      if (!isCurrent(sessionId, epoch)) return
      if (event.run_id) run.value = { ...run.value, runId: event.run_id }
      if (event.status) run.value = { ...run.value, status: event.status }
      if (event.reason) run.value = { ...run.value, reason: event.reason }
      if (Array.isArray(event.actions)) run.value = { ...run.value, actions: event.actions.filter(action=>actionLabels[action]) }
      if (event.type === 'status') progressText.value = typeof event.content === 'string' ? event.content : customerStatusText(event.status)
      else if (event.type === 'message') { receivedMessage = true; addPublishedMessage(event) }
      else if (event.type === 'interrupted') {
        receivedInterrupt = true
        run.value = { ...run.value, status: 'waiting_human', humanTaskId: event.human_task_id, revision: event.revision }
      } else if (event.type === 'error') streamFailure = typeof event.content === 'string' ? event.content : '本次处理未完成，请刷新查看进度。'
      else if (event.type === 'done') receivedDone = true
      else if (event.type === 'legacy_chunk') { currentStreamingText.value += event.content; scrollToBottom() }
    })
    try {
      while (!receivedDone) {
        const { done, value } = await reader.read()
        if (done || !isCurrent(sessionId, epoch)) break
        parser.push(decoder.decode(value, { stream: true }))
      }
      parser.push(decoder.decode())
      parser.finish()
    } finally { await reader.cancel().catch(() => {}) }
    if (!isCurrent(sessionId, epoch)) return
    if (streamFailure) throw new Error(streamFailure)
    if (currentStreamingText.value) {
      addPublishedMessage({ content: currentStreamingText.value })
      currentStreamingText.value = ''
      receivedMessage = true
    }
    if (!receivedDone) throw new Error('连接已断开，正在核对最新处理结果。')
    if (!receivedMessage && !receivedInterrupt && !supplement && !['waiting_user', 'waiting_human', 'cancelled'].includes(run.value?.status)) {
      throw new Error('尚未收到回复，正在核对最新处理结果。')
    }
    const session = sessions.value.find(item => item.id === sessionId)
    if (session?.title === '新对话') session.title = text.length > 20 ? `${text.substring(0, 20)}…` : text
    if (supplement) ElMessage.success('已补充给人工处理人员')
    await refreshSnapshot(sessionId, epoch)
  } catch (error) {
    if (!isCurrent(sessionId, epoch) || error.name === 'AbortError') return
    const failure = errorText(error, '客服连接暂不可用，请刷新查看处理结果。')
    await refreshSnapshot(sessionId, epoch, true)
    if (isCurrent(sessionId, epoch)) deliveryError.value = failure
  } finally {
    if (isCurrent(sessionId, epoch)) {
      loading.value = false
      currentStreamingText.value = ''
      streamAbort = null
      schedulePoll(sessionId, epoch)
      scrollToBottom()
    }
  }
}

function ensureLiveSocket(sessionId,epoch){
  const id=liveSession.value?.id
  if(liveClient&&liveSocketId===id)return
  liveClient?.close();liveSocketId=id
  liveClient=connectLive({onStatus:value=>{if(isCurrent(sessionId,epoch))liveConnection.value=value},
    onOpen:()=>refreshSnapshot(sessionId,epoch,true),
    onEvent:event=>{if(['chat.message','session.state','session.closed'].includes(event.type)&&Number(event.data?.sessionId)===Number(id))return refreshSnapshot(sessionId,epoch,true)}})
}
async function sendLiveMessage(content){
  const sessionId=currentSessionId.value, epoch=sessionEpoch
  if(content.length>2000){deliveryError.value='人工消息最多两千字';return}
  if(!pendingLive||pendingLive.content!==content)pendingLive={sessionId:liveSession.value.id,content,contentType:'text',clientMsgId:crypto.randomUUID().replaceAll('-','')}
  loading.value=true;deliveryError.value=''
  try{await liveClient.request('chat.send',pendingLive);if(isCurrent(sessionId,epoch)){inputMessage.value='';pendingLive=null;await refreshSnapshot(sessionId,epoch,true)}}
  catch(e){if(isCurrent(sessionId,epoch))deliveryError.value=errorText(e,'发送结果尚未确认，请重连后核对')}
  finally{if(isCurrent(sessionId,epoch))loading.value=false}
}
async function performAction(action){
  if(actionBusy.value||loading.value)return
  if(!currentSessionId.value)await handleCreateSession()
  if(!currentSessionId.value)return
  actionBusy.value=true;deliveryError.value=''
  try{
    if(action==='handoff'){
      const id=currentSessionId.value
      await handoffCustomer(id)
      stopActivity();currentSessionId.value=id
      await refreshSnapshot(id,sessionEpoch)
    }else{
      const res=await getOrderList();orders.value=Array.isArray(res.data)?res.data:(res.data?.records||[])
      selectedOrder.value=null;afterReason.value='';pendingAfter=null;afterSalesMode.value=action==='request_after_sales';ordersVisible.value=true
    }
  }catch(e){deliveryError.value=errorText(e,'操作暂未完成，请刷新核对')}finally{actionBusy.value=false}
}
async function endLive(){
  if(!liveSession.value||actionBusy.value)return
  actionBusy.value=true
  try{await closeCustomerLive(liveSession.value.id);await refreshSnapshot(currentSessionId.value,sessionEpoch)}catch(e){deliveryError.value=errorText(e,'结束结果尚未确认，请刷新核对')}finally{actionBusy.value=false}
}
async function confirmAfterSales(){
  if(!eligibleOrder.value||!afterReason.value.trim()||actionBusy.value)return
  const payload={order_id:selectedOrder.value.id,reason:afterReason.value.trim()}, signature=JSON.stringify(payload)
  if(!pendingAfter||pendingAfter.signature!==signature)pendingAfter={signature,id:crypto.randomUUID().replaceAll('-','')}
  actionBusy.value=true
  try{const res=await submitAfterSales(currentSessionId.value,{...payload,request_id:pendingAfter.id});ElMessage.success(`${res.data.message} 工单编号：${res.data.ticket_id}`);ordersVisible.value=false;pendingAfter=null;await refreshSnapshot(currentSessionId.value,sessionEpoch)}catch(e){ElMessage.error(errorText(e,'提交结果尚未确认，请核对后重试'))}finally{actionBusy.value=false}
}

async function cancelRun() {
  if (!currentSessionId.value || cancelling.value || !canCancel.value) return
  const sessionId = currentSessionId.value
  const epoch = sessionEpoch
  cancelling.value = true
  try {
    await cancelCustomerRun(sessionId)
    if (!isCurrent(sessionId, epoch)) return
    stopActivity()
    run.value = { ...run.value, status: 'cancelled' }
    await refreshSnapshot(sessionId, sessionEpoch)
  } catch (error) { if (isCurrent(sessionId, epoch)) deliveryError.value = errorText(error, '取消结果尚未确认，请刷新核对') }
  finally { cancelling.value = false }
}

watch(visible, value => { if (!value) { ++openSequence; stopActivity() } })
onUnmounted(() => { ++openSequence; stopActivity() })
defineExpose({ open })
</script>

<style scoped>
.action-hint { width: 100%; color: #426953; font-size: 12px; }
.customer-actions{display:flex;gap:6px;padding:8px 14px;flex-wrap:wrap;border-top:1px solid #eee}.customer-actions button{border:1px solid #c7d9cd;background:#fff;color:#21694f;border-radius:16px;padding:6px 10px;cursor:pointer}.customer-actions button.suggested{background:#e5f2e9;font-weight:600}.customer-actions button:disabled{opacity:.5;cursor:default}
/* ============ 浮窗面板：原型 C · 导购伙伴 ============ */
.cs-panel {
  position: fixed;
  right: 70px;
  bottom: 24px;
  z-index: 100;
  width: 352px;
  height: 570px;
  max-width: calc(100vw - 32px);
  max-height: calc(100vh - 48px);
  border-radius: 18px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background: #fbfaf7;
  border: 1px solid #e7e3da;
  box-shadow: 0 20px 60px rgba(40, 38, 30, .18);
}

.cs-panel-enter-active, .cs-panel-leave-active {
  transition: opacity .25s ease, transform .25s ease;
}
.cs-panel-enter-from, .cs-panel-leave-to {
  opacity: 0;
  transform: translateY(16px) scale(.98);
}

/* 头部 */
.hd {
  padding: 16px 18px 14px;
  display: flex;
  align-items: center;
  gap: 11px;
  background: #fbfaf7;
  border-bottom: 1px solid #efece5;
  flex-shrink: 0;
}
.hd .face {
  width: 40px;
  height: 40px;
  border-radius: 14px;
  background: linear-gradient(135deg, #21694f, #2b8966);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 15px;
  font-weight: 800;
  position: relative;
}
.hd .face::after {
  content: "";
  position: absolute;
  right: -2px;
  bottom: -2px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #4ec984;
  border: 2px solid #fbfaf7;
}
.hd-text { flex: 1; min-width: 0; }
.hd .name { font-size: 14.5px; font-weight: 700; color: #2a2823; }
.hd .stat { margin-top: 2px; font-size: 11px; color: #9a9487; }
.hd .stat::before {
  content: "●";
  color: #4ec984;
  font-size: 8px;
  margin-right: 5px;
  vertical-align: 1px;
}
.hd .acts { display: flex; gap: 2px; }
.hd .acts button {
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 9px;
  cursor: pointer;
  background: transparent;
  color: #8b857a;
  font-size: 15px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.hd .acts button:hover { background: #f0ede5; color: #2a2823; }

/* 消息区：无框气泡 */
.msgs {
  flex: 1;
  overflow-y: auto;
  padding: 16px 16px 8px;
}
.welcome { padding-top: 56px; text-align: center; }
.welcome .hi { font-size: 20px; font-weight: 800; color: #2a2823; }
.welcome .hi em { font-style: normal; color: #21694f; }
.welcome p { margin-top: 8px; font-size: 12.5px; color: #9a9487; }
.welcome .q {
  margin-top: 22px;
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  justify-content: center;
}
.welcome .q button {
  padding: 7px 14px;
  font-size: 12px;
  cursor: pointer;
  background: #fff;
  border: 1px solid #e7e3da;
  border-radius: 20px;
  color: #6f6a5c;
  transition: all .15s;
}
.welcome .q button:hover {
  border-color: #21694f;
  color: #21694f;
  transform: translateY(-1px);
}

.row { display: flex; gap: 9px; margin-bottom: 15px; }
.row.u { flex-direction: row-reverse; }
.row.b .av {
  width: 30px;
  height: 30px;
  border-radius: 11px;
  flex-shrink: 0;
  background: linear-gradient(135deg, #21694f, #2b8966);
  color: #fff;
  font-size: 9.5px;
  font-weight: 800;
  display: flex;
  align-items: center;
  justify-content: center;
}
.bb {
  max-width: 78%;
  font-size: 13.5px;
  line-height: 1.65;
  word-break: break-word;
  white-space: pre-wrap;
}
.row.b .bb { color: #33302a; padding: 2px 0; }
.row.u .bb {
  background: #191917;
  color: #f5f3ee;
  padding: 9px 14px;
  border-radius: 16px 16px 5px 16px;
}
.bb.streaming::after {
  content: "▌";
  color: #21694f;
  animation: cs-blink 1s infinite;
}
@keyframes cs-blink { 50% { opacity: 0; } }
.dots span {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 4px;
  background: #c9c2b4;
  border-radius: 50%;
  animation: cs-bounce 1.2s infinite;
}
.dots span:nth-child(2) { animation-delay: .15s; }
.dots span:nth-child(3) { animation-delay: .3s; }
.thinking-text { display: inline-block; margin-left: 5px; color: #827d72; font-size: 12px; }
.run-notice { padding: 10px 16px; border-top: 1px solid #e7e3da; background: #f0f5ed; color: #365947; font-size: 12px; flex-shrink: 0; }
.run-notice p { margin: 0; line-height: 1.6; }
.run-notice .notice-help { margin-top: 3px; color: #68776d; }
.run-notice .notice-error { color: #a33e2f; margin-top: 3px; }
.notice-actions { display: flex; flex-wrap: wrap; gap: 14px; margin-top: 6px; }
.notice-actions button { padding: 2px 0; border: 0; background: none; color: #21694f; cursor: pointer; font: inherit; text-decoration: underline; text-underline-offset: 3px; }
.notice-actions button:disabled { cursor: default; opacity: .5; }
.notice-actions button:focus-visible { outline: 2px solid #21694f; outline-offset: 3px; }
@keyframes cs-bounce {
  0%, 60%, 100% { transform: none; }
  30% { transform: translateY(-4px); }
}

/* 输入：药丸框 */
.ft { padding: 10px 14px 14px; flex-shrink: 0; }
.ft .pill {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  background: #fff;
  border: 1px solid #e7e3da;
  border-radius: 22px;
  padding: 6px 6px 6px 16px;
  transition: border-color .15s, box-shadow .15s;
}
.ft .pill:focus-within {
  border-color: #c9c2b4;
  box-shadow: 0 4px 14px rgba(40, 38, 30, .08);
}
.ft textarea {
  flex: 1;
  resize: none;
  height: 34px;
  max-height: 90px;
  padding: 8px 0;
  border: none;
  outline: none;
  background: transparent;
  font: 13.5px/1.4 inherit;
  color: #2a2823;
}
.ft .send {
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 50%;
  cursor: pointer;
  flex-shrink: 0;
  background: #21694f;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: transform .15s, opacity .15s;
}
.ft .send:hover:not(:disabled) { transform: scale(1.08); }
.ft .send:disabled { opacity: .45; cursor: not-allowed; }

/* 历史抽屉（底部上滑） */
.drawer {
  position: absolute;
  inset: 0;
  z-index: 3;
  background: rgba(30, 28, 24, .25);
  opacity: 0;
  pointer-events: none;
  transition: opacity .2s;
}
.drawer.open { opacity: 1; pointer-events: auto; }
.drawer .sheet {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  max-height: 82%;
  background: #fbfaf7;
  border-radius: 18px 18px 0 0;
  transform: translateY(100%);
  transition: transform .28s ease;
  display: flex;
  flex-direction: column;
  box-shadow: 0 -8px 30px rgba(40, 38, 30, .15);
}
.drawer.open .sheet { transform: none; }
.sheet .grab {
  width: 36px;
  height: 4px;
  border-radius: 2px;
  background: #ddd8cd;
  margin: 10px auto 4px;
}
.sheet .sh {
  padding: 6px 18px 10px;
  display: flex;
  align-items: center;
}
.sheet .sh h3 { font-size: 14px; font-weight: 700; color: #2a2823; margin: 0; }
.sheet .sh .new {
  margin-left: auto;
  border: none;
  cursor: pointer;
  background: #191917;
  color: #fff;
  font: 600 11.5px/1 inherit;
  padding: 7px 13px;
  border-radius: 14px;
}
.sheet .sh .new:hover { background: #21694f; }
.sheet .list { overflow-y: auto; padding: 2px 12px 14px; }
.sh-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 12px;
  border-radius: 11px;
  cursor: pointer;
}
.sh-item:hover { background: #f2efe8; }
.sh-item.cur { background: #fdf1f0; }
.sh-item .ci {
  width: 34px;
  height: 34px;
  border-radius: 11px;
  flex-shrink: 0;
  background: #efeadf;
  color: #8b857a;
  display: flex;
  align-items: center;
  justify-content: center;
}
.sh-item.cur .ci { background: #f9ddd9; color: #21694f; }
.sh-item .ti {
  flex: 1;
  font-size: 13px;
  color: #3a3630;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sh-item.cur .ti { color: #21694f; font-weight: 600; }
.sh-item .del {
  border: 0;
  background: transparent;
  cursor: pointer;
  color: #ccc6b8;
  font-size: 15px;
  padding: 3px;
  line-height: 1;
}
.sh-item .del:hover { color: #21694f; }
.empty {
  text-align: center;
  color: #9a9487;
  font-size: 12px;
  padding: 24px 0;
}
@media (max-width: 640px) {
  .cs-panel { right: 12px; bottom: 76px; width: calc(100vw - 24px); height: min(570px, calc(100dvh - 92px)); }
}
</style>
