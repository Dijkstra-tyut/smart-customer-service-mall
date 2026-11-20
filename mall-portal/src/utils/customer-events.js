const eventTypes = new Set(['status', 'message', 'interrupted', 'error', 'done'])

// Only published message events become chat content. Unknown JSON is never rendered.
export function decodeCustomerEvent(frame) {
  const data = frame.split(/\r?\n/).filter(line => line.startsWith('data:'))
    .map(line => line.slice(5).replace(/^ /, '')).join('\n')
  if (!data) return null
  if (data === '[DONE]') return { type: 'done' }
  try {
    const value = JSON.parse(data)
    if (!value || typeof value !== 'object' || Array.isArray(value)) return null
    if (eventTypes.has(value.type)) return value
    if (!value.type && typeof value.content === 'string') return { type: 'legacy_chunk', content: value.content }
    return null
  } catch {
    // Retain plain-text compatibility for the previous Java stream, not malformed JSON.
    return /^[\s]*[\[{]/.test(data) ? null : { type: 'legacy_chunk', content: data }
  }
}

export function createCustomerEventParser(onEvent) {
  let buffer = ''
  const emit = (frame) => { const event = decodeCustomerEvent(frame); if (event) onEvent(event) }
  return {
    push(text) {
      buffer += text
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() || ''
      frames.forEach(emit)
    },
    finish() { if (buffer.trim()) emit(buffer); buffer = '' }
  }
}

export function customerStatusText(status, reason) {
  if (status === 'waiting_human') {
    return reason === 'sensitive_action' ? '这项业务需要人工确认，已提交处理。'
      : reason === 'review_failed' ? '为确保答复准确，已提交人工核对。' : '已提交人工处理，请稍候。'
  }
  return { running: '正在处理你的问题…', waiting_user: '请补充所需信息，我们会继续处理。', cancelled: '本次处理已取消。', failed: '本次自动处理未完成，可刷新查看结果或稍后再试。' }[status] || ''
}
