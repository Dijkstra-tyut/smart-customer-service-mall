// 门户和管理端共用：登录 Cookie 自动完成握手；重连后由页面补读数据库消息。
export function connectLive({ onEvent, onOpen, onStatus }) {
  let socket, stopped = false, retryTimer, heartbeat, attempt = 0
  const requests = new Map()
  const failPending = () => {
    for (const pending of requests.values()) { clearTimeout(pending.timer); pending.reject(new Error('连接已断开，请重连后核对消息')) }
    requests.clear()
  }
  function open() {
    if (stopped) return
    onStatus?.('connecting')
    socket = new WebSocket(`${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws/cs`)
    socket.onopen = () => {
      attempt = 0
      onStatus?.('connected')
      heartbeat = setInterval(() => {
        if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: 'ping', data: {} }))
      }, 25000)
      Promise.resolve(onOpen?.()).catch(() => onStatus?.('sync_failed'))
    }
    socket.onmessage = event => {
      let message
      try { message = JSON.parse(event.data) } catch { return }
      const pending = requests.get(message.requestId)
      if (pending) {
        clearTimeout(pending.timer)
        requests.delete(message.requestId)
        if (message.type === 'error') pending.reject(new Error(message.data?.message || '消息提交失败'))
        else pending.resolve(message.data)
      }
      Promise.resolve(onEvent?.(message)).catch(() => onStatus?.('sync_failed'))
    }
    socket.onclose = () => {
      clearInterval(heartbeat)
      failPending()
      if (!stopped) {
        onStatus?.('reconnecting')
        retryTimer = setTimeout(open, Math.min(1000 * 2 ** attempt++, 10000))
      }
    }
    socket.onerror = () => socket.close()
  }
  open()
  return {
    request(type, data = {}) {
      if (socket?.readyState !== WebSocket.OPEN) return Promise.reject(new Error('正在连接人工客服，请稍候'))
      const requestId = crypto.randomUUID().replaceAll('-', '')
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => { requests.delete(requestId); reject(new Error('发送结果尚未确认，请核对会话后重试')) }, 10000)
        requests.set(requestId, { resolve, reject, timer })
        socket.send(JSON.stringify({ type, requestId, data }))
      })
    },
    close() { stopped = true; clearTimeout(retryTimer); clearInterval(heartbeat); failPending(); socket?.close() }
  }
}
