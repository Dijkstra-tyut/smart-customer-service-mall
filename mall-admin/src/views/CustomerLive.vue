<template>
  <div class="page-container">
    <div class="live-heading"><h1>人工实时客服</h1><el-tag>{{ connectionText }}</el-tag><el-button @click="loadQueue">刷新列表</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="live-grid">
      <el-card shadow="never">
        <el-select v-model="status" aria-label="会话状态筛选" @change="loadQueue"><el-option label="待接入和服务中" value="active" /><el-option label="已结束" value="closed" /></el-select>
        <div v-for="item in sessions" :key="item.id" :class="['queue-item',{selected:selected?.id===item.id}]" @click="select(item)">
          <strong>{{ item.userName || `用户 ${item.userId}` }}</strong><el-tag size="small">{{ statusText(item.status) }}</el-tag>
          <p>{{ item.lastMessagePreview || '用户已申请人工服务' }}</p>
          <small>会话 {{ item.id }} · {{ item.adminName || '等待接入' }}</small>
        </div>
        <el-empty v-if="!sessions.length" description="暂无会话" />
        <el-pagination v-if="total>20" v-model:current-page="page" :page-size="20" :total="total" layout="prev,pager,next" @current-change="loadQueue" />
      </el-card>
      <el-card shadow="never">
        <template v-if="selected">
          <div class="live-heading"><strong>{{ selected.userName }} · 会话 {{ selected.id }}</strong><el-tag>{{ statusText(selected.status) }}</el-tag>
            <el-button v-if="selected.status==='waiting'" type="primary" @click="claim">接入会话</el-button>
            <el-button v-if="canReply" @click="close">结束服务</el-button>
          </div>
          <el-collapse v-if="context.length"><el-collapse-item title="转接前的智能客服对话"><p v-for="(message,index) in context" :key="index"><strong>{{ message.type==='user'?'用户':'客服' }}：</strong>{{ message.content }}</p></el-collapse-item></el-collapse>
          <div class="live-messages" ref="messageBox" role="log" aria-label="实时客服消息">
            <div v-for="message in messages" :key="message.id" :class="['live-message',{mine:message.senderType==='admin'}]"><small>{{ message.senderType==='user'?'用户':message.senderType==='admin'?'客服':'系统' }}</small><p>{{ message.content }}</p></div>
            <el-empty v-if="!messages.length" description="等待用户发送消息" />
          </div>
          <p v-if="selected.status==='serving'&&!canReply">该会话已由其他客服接入。</p>
          <el-input v-model="text" type="textarea" :rows="3" maxlength="2000" aria-label="实时回复内容" placeholder="先接入会话，再回复用户" :disabled="!canReply||sending" @keydown.ctrl.enter.prevent="send" />
          <el-button type="primary" :disabled="!canReply||!text.trim()||connection!=='connected'" :loading="sending" @click="send">发送实时回复</el-button>
        </template>
        <el-empty v-else description="从左侧选择会话" />
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/modules/user'
import { listLiveSessions, getLiveSession, getLiveMessages, getLiveContext, claimLiveSession, closeLiveSession } from '@/api/live'
import { connectLive } from '../../../shared/live-client.js'

const user = useUserStore()
const sessions=ref([]), selected=ref(null), messages=ref([]), context=ref([]), text=ref(''), error=ref(''), connection=ref('connecting'), sending=ref(false), status=ref('active'), page=ref(1), total=ref(0), messageBox=ref(null)
const canReply=computed(()=>selected.value?.status==='serving'&&Number(selected.value.adminId)===Number(user.user?.id))
const connectionText=computed(()=>({connected:'实时连接正常',connecting:'正在连接',reconnecting:'正在重连',sync_failed:'记录同步失败，请刷新'})[connection.value]||'连接已断开')
const statusText=value=>({waiting:'等待接入',serving:'服务中',closed:'已结束'})[value]||value
let client, selection=0, pending=null
const report=err=>{error.value=err?.message||'操作暂未完成，请刷新核对'}
async function loadQueue(){try{const res=await listLiveSessions({status:status.value,page:page.value,size:20});sessions.value=res.data.records;total.value=res.data.total}catch(e){report(e)}}
async function sync(){
  const id=selected.value?.id, version=selection
  if(!id)return
  try{
    const current=await getLiveSession(id)
    let after=0, all=[]
    while(true){const res=await getLiveMessages(id,after);const batch=res.data||[];all.push(...batch);if(batch.length<100)break;after=batch.at(-1).id}
    if(selection!==version)return
    selected.value=current.data;messages.value=all
    await nextTick();if(messageBox.value)messageBox.value.scrollTop=messageBox.value.scrollHeight
  }catch(e){report(e)}
}
async function select(item){selection++;selected.value=item;messages.value=[];context.value=[];text.value='';pending=null;error.value='';await sync();const id=item.id;try{const res=await getLiveContext(id);if(selected.value?.id===id)context.value=res.data||[]}catch(e){report(e)}}
async function claim(){try{await claimLiveSession(selected.value.id);await sync();await loadQueue()}catch(e){report(e)}}
async function close(){try{await ElMessageBox.confirm('结束本次人工服务，用户之后可以继续使用智能客服。','结束服务');await closeLiveSession(selected.value.id);await sync();await loadQueue()}catch(e){if(e!=='cancel'&&e!=='close')report(e)}}
async function send(){
  if(!canReply.value||!text.value.trim()||sending.value)return
  const id=selected.value.id, content=text.value.trim();sending.value=true;error.value=''
  if(!pending||pending.sessionId!==id||pending.content!==content)pending={sessionId:id,content,contentType:'text',clientMsgId:crypto.randomUUID().replaceAll('-','')}
  try{await client.request('chat.send',pending);if(selected.value?.id===id)text.value='';pending=null;await sync()}catch(e){report(e)}finally{sending.value=false}
}
onMounted(()=>{client=connectLive({onStatus:value=>connection.value=value,onOpen:async()=>{await loadQueue();await sync()},onEvent:async event=>{if(['session.waiting','session.state','session.closed','chat.message'].includes(event.type)){await loadQueue();if(Number(event.data?.sessionId)===Number(selected.value?.id))await sync()}}})})
onUnmounted(()=>{selection++;client?.close()})
</script>

<style scoped>
.live-heading{display:flex;align-items:center;gap:12px;margin-bottom:16px;flex-wrap:wrap}.live-heading h1{font-size:22px;margin:0}.live-grid{display:grid;grid-template-columns:300px 1fr;gap:16px}.queue-item{padding:14px 8px;border-bottom:1px solid #eee;cursor:pointer}.queue-item.selected{background:#eef7f1}.queue-item .el-tag{float:right}.queue-item p{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;margin:8px 0;color:#555}.queue-item small{color:#888}.live-messages{height:48vh;overflow:auto;background:#f6f8f7;padding:14px;margin:12px 0}.live-message{max-width:85%;margin:0 0 14px}.live-message.mine{margin-left:auto;text-align:right}.live-message small{color:#777}.live-message p{background:white;padding:12px;border-radius:8px;margin:4px 0;white-space:pre-wrap;text-align:left}.live-message.mine p{background:#e1f1e7}.el-textarea+.el-button{margin-top:12px}@media(max-width:800px){.live-grid{grid-template-columns:1fr}.live-messages{height:40vh}}
</style>
