<template>
  <div class="page-container customer-tasks">
    <el-card shadow="never" class="task-intro">
      <div class="task-heading">
        <div>
          <h1>客服人工任务</h1>
          <p>查看客户诉求和处理依据，确认业务操作或编写客服回复。</p>
        </div>
        <el-button :loading="listLoading" @click="loadTasks">刷新列表</el-button>
      </div>
      <el-form :inline="true" class="task-filters">
        <el-form-item label="任务状态">
          <el-select v-model="query.status" style="width: 180px" @change="changeFilter">
            <el-option v-for="(label, value) in statusLabels" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-alert v-if="listError" :title="listError" type="error" :closable="false" show-icon />
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="listLoading" :data="tasks" row-key="id" @row-dblclick="openTask">
        <el-table-column prop="id" label="任务编号" min-width="170" show-overflow-tooltip />
        <el-table-column label="转人工原因" min-width="140">
          <template #default="{ row }">{{ reasonLabel(row.reason) }}</template>
        </el-table-column>
        <el-table-column prop="userId" label="客户编号" width="110" />
        <el-table-column label="状态" min-width="130">
          <template #default="{ row }"><el-tag :type="taskTagType(row.status)">{{ statusLabel(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="提交时间" min-width="170"><template #default="{ row }">{{ row.createdAt || row.createTime || '—' }}</template></el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }"><el-button link type="primary" @click="openTask(row)">查看处理</el-button></template>
        </el-table-column>
        <template #empty><el-empty description="当前没有此状态的客服任务" /></template>
      </el-table>
      <div class="page-pagination">
        <el-pagination v-model:current-page="query.page" v-model:page-size="query.size" :total="total"
          :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next" @current-change="loadTasks" @size-change="changeFilter" />
      </div>
    </el-card>

    <el-drawer v-model="detailVisible" title="人工处理" size="min(900px, 96vw)" :close-on-click-modal="!submitting" :before-close="beforeClose">
      <div v-loading="detailLoading" class="task-detail">
        <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" show-icon class="detail-alert" />
        <template v-if="task">
          <div class="detail-heading">
            <el-tag :type="taskTagType(task.status)">{{ statusLabel(task.status) }}</el-tag>
            <el-button :disabled="submitting" :loading="detailLoading" @click="refreshTask">刷新现场</el-button>
          </div>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="任务编号">{{ task.id }}</el-descriptions-item>
            <el-descriptions-item label="转人工原因">{{ reasonLabel(task.reason) }}</el-descriptions-item>
            <el-descriptions-item label="客户编号">{{ task.userId }}</el-descriptions-item>
            <el-descriptions-item label="会话编号">{{ task.sessionId }}</el-descriptions-item>
          </el-descriptions>
          <el-alert v-if="task.actionStale" title="客户已补充信息，原业务方案需要重新核对。当前不能批准原方案，可以确认回复或要求客户补充信息。"
            type="warning" :closable="false" show-icon class="detail-alert" />
          <el-alert v-if="task.error" :title="`处理未完成：${task.error}`" type="error" :closable="false" show-icon class="detail-alert" />

          <h2>客户会话</h2>
          <div class="task-transcript" role="log" aria-label="客户会话记录">
            <div v-for="(message, index) in conversation" :key="message.id || index" :class="['transcript-message', { customer: message.type === 'user' || message.role === 'user' }]">
              <strong>{{ message.type === 'user' || message.role === 'user' ? '客户' : '客服' }}</strong>
              <p>{{ message.content }}</p>
            </div>
            <el-empty v-if="!conversation.length" description="暂无会话记录" :image-size="70" />
          </div>

          <template v-if="proposal">
            <h2>待确认的业务操作</h2>
            <div class="task-evidence">
              <strong>{{ actionLabel(proposal.kind || proposal.action) }}</strong>
              <dl class="action-arguments"><template v-for="field in proposalFields" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ field.value }}</dd></template></dl>
            </div>
          </template>
          <el-collapse class="task-sources">
            <el-collapse-item title="处理依据与审查意见" name="evidence">
              <h3>审查意见</h3><pre>{{ pretty(task.review) }}</pre>
              <h3>参考依据</h3><pre>{{ pretty(task.evidence) }}</pre>
            </el-collapse-item>
          </el-collapse>

          <h2>客服回复</h2>
          <p class="field-help">确认发送后按此内容回复客户。编辑回复不会批准任何业务操作。</p>
          <el-input v-model="reply" type="textarea" :rows="7" maxlength="10000" show-word-limit
            aria-label="客服回复" placeholder="填写或修改要发送给客户的回复，也可填写需要客户补充的问题" :disabled="!canDecide || submitting" />
          <el-form label-position="top" class="decision-comment">
            <el-form-item label="处理说明（拒绝业务操作时必填）">
              <el-input v-model="comment" type="textarea" :rows="2" maxlength="2000" placeholder="说明判断依据或补充要求" :disabled="!canDecide || submitting" />
            </el-form-item>
          </el-form>
          <el-alert v-if="recordedDecision" :title="`已记录：${decisionLabel(recordedDecision.action)}。请等待处理结果；交付失败时可重试已记录决定。`"
            type="info" :closable="false" show-icon />
          <div class="decision-actions">
            <el-button type="primary" :loading="submitting && currentAction === 'send_reply'" :disabled="!canDecide || submitting || !reply.trim()" @click="submitDecision('send_reply')">确认并发送回复</el-button>
            <el-button :disabled="!canDecide || submitting || !reply.trim()" @click="submitDecision('request_info')">请客户补充信息</el-button>
            <template v-if="proposal">
              <el-button type="warning" :disabled="!canDecide || submitting || task.actionStale" @click="submitDecision('approve_action')">批准此业务操作</el-button>
              <el-button type="danger" plain :disabled="!canDecide || submitting || !comment.trim()" @click="submitDecision('reject_action')">拒绝业务操作</el-button>
            </template>
            <el-button v-if="canRetry" :loading="submitting" @click="retryDelivery">重试已记录决定</el-button>
          </div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { decideCustomerTask, getCustomerTask, listCustomerTasks, retryCustomerTask } from '@/api/customer'

const statusLabels = { pending: '待人工处理', preparing: '正在提交', decision_recorded: '决定已记录', applying: '正在恢复处理', resume_failed: '恢复待重试', applied: '已完成', resolved: '已完成', cancelled: '已取消' }
const reasonLabels = { sensitive_action: '敏感业务操作', user_requested: '客户要求人工', review_failed: '自动审查未通过' }
const decisionLabels = { approve_action: '批准业务操作', reject_action: '拒绝业务操作', send_reply: '确认客服回复', request_info: '请客户补充信息' }
const query = reactive({ status: 'pending', page: 1, size: 20 })
const tasks = ref([])
const total = ref(0)
const listLoading = ref(false)
const listError = ref('')
const detailVisible = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const task = ref(null)
const reply = ref('')
const comment = ref('')
const submitting = ref(false)
const currentAction = ref('')
let detailSequence = 0
let listSequence = 0
let pendingSubmission = null
let refreshTimer = null

const parse = (value) => { if (typeof value !== 'string') return value; try { return JSON.parse(value) } catch { return value } }
const proposal = computed(() => { const value = parse(task.value?.actionProposal); return value && typeof value === 'object' && (value.kind || value.action) ? value : null })
const proposalFields = computed(() => Object.entries(proposal.value?.arguments || {}).map(([key, value]) => ({
  key, label: { order_id: '订单编号', ticket_id: '工单编号', summary: '诉求摘要', details: '详细说明', priority: '优先级', status: '工单状态' }[key] || key,
  value: typeof value === 'object' ? JSON.stringify(value) : key === 'priority' ? ({ low: '普通', medium: '中等', high: '高', urgent: '紧急' }[value] || value) : value
})))
const conversation = computed(() => { const value = parse(task.value?.conversation); return Array.isArray(value) ? value : [] })
const recordedDecision = computed(() => { const value = parse(task.value?.decision); return value?.action ? value : null })
const canDecide = computed(() => task.value?.status === 'pending')
const canRetry = computed(() => ['decision_recorded', 'resume_failed'].includes(task.value?.status))
const statusLabel = (value) => statusLabels[value] || '处理中'
const reasonLabel = (value) => reasonLabels[value] || '需要人工协助'
const decisionLabel = (value) => decisionLabels[value] || '人工决定'
const taskTagType = (value) => ['applied', 'resolved'].includes(value) ? 'success' : value === 'resume_failed' ? 'danger' : value === 'pending' ? 'warning' : 'info'
const actionLabel = (value) => ({ order_cancel: '取消订单', ticket_create: '创建售后工单', ticket_update: '更新售后工单' }[value] || '待核对的业务操作')
const pretty = (value) => { const parsed = parse(value); return parsed == null || parsed === '' ? '暂无' : typeof parsed === 'string' ? parsed : JSON.stringify(parsed, null, 2) }
const errorText = (error, fallback) => typeof error === 'string' ? error : error?.message || fallback

async function loadTasks() {
  const sequence = ++listSequence
  listLoading.value = true
  listError.value = ''
  try {
    const response = await listCustomerTasks({ ...query })
    if (sequence !== listSequence) return
    tasks.value = response.data?.records || []
    total.value = response.data?.total || 0
  } catch (error) {
    if (sequence === listSequence) listError.value = errorText(error, '任务列表加载失败，请重试')
  } finally { if (sequence === listSequence) listLoading.value = false }
}

function changeFilter() { query.page = 1; loadTasks() }

async function openTask(row) {
  if (submitting.value) return
  task.value = null
  reply.value = ''
  comment.value = ''
  pendingSubmission = null
  detailVisible.value = true
  await fetchDetail(row.id, true)
}

async function fetchDetail(id, resetEditor = false) {
  const sequence = ++detailSequence
  detailLoading.value = true
  detailError.value = ''
  try {
    const response = await getCustomerTask(id)
    if (sequence !== detailSequence || !detailVisible.value) return
    const previous = task.value
    task.value = response.data
    if (resetEditor) reply.value = task.value?.draft || ''
    if (previous && previous.version !== task.value?.version) {
      pendingSubmission = null
      detailError.value = '客户信息或任务版本已更新，请核对最新会话后再提交。'
    }
    scheduleDetailRefresh()
  } catch (error) {
    if (sequence === detailSequence) detailError.value = errorText(error, '任务详情加载失败，请刷新')
  } finally { if (sequence === detailSequence) detailLoading.value = false }
}

function scheduleDetailRefresh() {
  clearTimeout(refreshTimer)
  if (detailVisible.value && task.value && ['decision_recorded', 'applying'].includes(task.value.status)) {
    refreshTimer = setTimeout(() => { if (!submitting.value) fetchDetail(task.value.id) }, 3000)
  }
}

function refreshTask() { if (task.value) fetchDetail(task.value.id) }

function createDecisionId() {
  return globalThis.crypto?.randomUUID?.() || `decision-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

async function submitDecision(action) {
  if (!canDecide.value || submitting.value) return
  if (['send_reply', 'request_info'].includes(action) && !reply.value.trim()) return
  if (action === 'reject_action' && !comment.value.trim()) return
  if (action === 'approve_action') {
    if (!proposal.value || task.value.actionStale) return
    try { await ElMessageBox.confirm('确认执行当前展示的业务操作？该决定只适用于当前方案。', '批准业务操作', { confirmButtonText: '确认批准', cancelButtonText: '返回核对', type: 'warning' }) }
    catch { return }
  }
  const id = task.value.id
  const payload = { version: task.value.version, action, comment: comment.value.trim() || undefined }
  if (['send_reply', 'request_info'].includes(action)) payload.reply = reply.value
  const fingerprint = JSON.stringify({ id, ...payload })
  if (!pendingSubmission || pendingSubmission.fingerprint !== fingerprint) {
    pendingSubmission = { fingerprint, payload: { ...payload, decisionId: createDecisionId() } }
  }
  submitting.value = true
  currentAction.value = action
  detailError.value = ''
  try {
    await decideCustomerTask(id, pendingSubmission.payload)
    ElMessage.success('人工决定已记录，正在处理')
    pendingSubmission = null
    await fetchDetail(id)
    await loadTasks()
  } catch (error) {
    detailError.value = errorText(error, '提交结果暂未确认，请刷新任务核对后重试')
    // Keep the same decisionId for an unchanged retry after an uncertain network result.
    const failure = detailError.value
    await fetchDetail(id)
    if (!detailError.value) detailError.value = failure
  } finally { submitting.value = false; currentAction.value = ''; scheduleDetailRefresh() }
}

async function retryDelivery() {
  if (!canRetry.value || submitting.value) return
  submitting.value = true
  detailError.value = ''
  const id = task.value.id
  try { await retryCustomerTask(id); ElMessage.success('已安排重试原决定'); await fetchDetail(id); await loadTasks() }
  catch (error) { detailError.value = errorText(error, '暂时无法安排重试，请稍后刷新') }
  finally { submitting.value = false; scheduleDetailRefresh() }
}

function beforeClose(done) {
  if (submitting.value) return
  ++detailSequence
  clearTimeout(refreshTimer)
  done()
}

onMounted(loadTasks)
onUnmounted(() => { ++detailSequence; ++listSequence; clearTimeout(refreshTimer) })
</script>

<style scoped>
.customer-tasks { display: grid; gap: 16px; }
.task-heading, .detail-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.task-heading h1 { margin: 0; color: var(--text-primary, #243b2d); font-size: 22px; }
.task-heading p, .field-help { color: var(--text-secondary, #68776d); font-size: 13px; line-height: 1.7; }
.task-filters { margin-top: 18px; }
.task-filters :deep(.el-form-item) { margin-bottom: 0; }
.task-detail { min-height: 240px; }
.detail-heading { margin-bottom: 16px; }
.detail-alert { margin: 16px 0; }
.task-detail h2 { font-size: 16px; margin: 24px 0 12px; color: var(--text-primary, #243b2d); }
.task-detail h3 { font-size: 13px; margin: 12px 0 8px; }
.task-transcript { max-height: 300px; overflow: auto; border: 1px solid var(--border-light, #e1e8e2); border-radius: 8px; padding: 12px; }
.transcript-message { padding: 10px; margin-bottom: 8px; background: var(--bg-page, #f4f7f4); border-radius: 6px; }
.transcript-message.customer { border-left: 3px solid var(--color-primary, #327457); }
.transcript-message strong { font-size: 12px; color: var(--text-secondary, #68776d); }
.transcript-message p { margin: 5px 0 0; font-size: 14px; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.7; }
.task-evidence { background: var(--bg-page, #f4f7f4); padding: 12px; border-radius: 8px; }
.action-arguments { display: grid; grid-template-columns: minmax(80px, auto) 1fr; gap: 8px 14px; font-size: 13px; margin: 12px 0 0; }
.action-arguments dt { color: var(--text-secondary, #68776d); }
.action-arguments dd { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.task-detail pre { margin: 8px 0; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.7 inherit; }
.task-sources { margin-top: 16px; }
.decision-comment { margin-top: 20px; }
.decision-actions { display: flex; flex-wrap: wrap; gap: 10px; margin: 20px 0; }
.decision-actions :deep(.el-button) { margin-left: 0; }
@media (max-width: 640px) { .task-heading { align-items: flex-start; } .task-heading h1 { font-size: 19px; } .decision-actions :deep(.el-button) { flex: 1 1 180px; } }
</style>
