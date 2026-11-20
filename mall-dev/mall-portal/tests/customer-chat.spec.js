import { test, expect } from '@playwright/test'

const sse = events => events.map(event => `data: ${JSON.stringify(event)}\n\n`).join('')

async function setup(page, chatHandler) {
  const state = { run: null, messages: { 1: [], 2: [{ id: 20, type: 'assistant', content: '另一个会话的回复' }] } }
  await page.addInitScript(() => localStorage.setItem('portal', JSON.stringify({ user: { id: 7, name: '测试用户' } })))
  await page.route('**/portal-api/**', async route => {
    const path = new URL(route.request().url()).pathname
    let data = []
    if (path === '/portal-api/customer') data = [{ id: 1, title: '第一个会话' }, { id: 2, title: '第二个会话' }]
    else if (path === '/portal-api/customer/chat') return chatHandler(route, state)
    else if (/\/customer\/\d+\/messages$/.test(path)) data = state.messages[path.split('/')[3]] || []
    else if (/\/customer\/\d+\/run$/.test(path)) data = path.includes('/1/') ? state.run : null
    else if (path === '/portal-api/product/home') data = []
    await route.fulfill({ json: { code: 200, message: '成功', data } })
  })
  await page.goto('/')
  await page.getByRole('button', { name: '打开智能客服', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '发送给客服的消息' })).toBeEnabled()
  return state
}

test('人工中断后连接可关闭，状态不混入聊天，轮询获得后续人工回复', async ({ page }) => {
  const state = await setup(page, async (route, state) => {
    const request = route.request().postDataJSON()
    state.messages[1] = [{ id: 1, type: 'user', content: request.message }]
    state.run = { runId: 'run-1', status: 'waiting_human', humanTaskId: 'task-1', reason: 'user_requested', revision: 1 }
    await route.fulfill({ contentType: 'text/event-stream', body: sse([
      { type: 'status', run_id: 'run-1', status: 'running', content: '这是进度，不是客服回复' },
      { type: 'interrupted', run_id: 'run-1', status: 'waiting_human', human_task_id: 'task-1', reason: 'user_requested' },
      { type: 'done', run_id: 'run-1', status: 'waiting_human' }
    ]) })
  })
  await page.getByRole('textbox', { name: '发送给客服的消息' }).fill('我要人工客服')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.getByText('已提交人工处理，请稍候。', { exact: true })).toBeVisible()
  await expect(page.locator('.msgs .bb')).toHaveCount(1)
  await expect(page.locator('.msgs')).not.toContainText('这是进度，不是客服回复')
  await expect(page.locator('.msgs')).not.toContainText('human_task_id')
  await page.locator('.cs-panel').screenshot({ path: 'test-results/customer/portal-waiting-human.png', animations: 'disabled' })
  state.messages[1].push({ id: 2, type: 'assistant', origin: 'human', content: '人工客服已核对，请提供包装照片。' })
  state.run = { ...state.run, status: 'completed' }
  await expect(page.locator('.msgs')).toContainText('人工客服已核对，请提供包装照片。', { timeout: 10000 })
  await expect(page.locator('.msgs .bb')).toHaveCount(2)
})

test('切换会话会忽略旧请求的迟到回复', async ({ page }) => {
  let finishOldRequest
  const oldPending = new Promise(resolve => { finishOldRequest = resolve })
  await setup(page, async route => {
    await oldPending
    await route.fulfill({ contentType: 'text/event-stream', body: sse([
      { type: 'message', run_id: 'old-run', status: 'completed', message_id: 'old-final', content: '旧请求迟到的回复' },
      { type: 'done', run_id: 'old-run', status: 'completed' }
    ]) }).catch(() => {})
  })
  await page.getByRole('textbox', { name: '发送给客服的消息' }).fill('查询订单')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await page.getByRole('button', { name: '查看历史会话' }).click()
  await page.getByText('第二个会话', { exact: true }).click()
  await expect(page.locator('.msgs')).toContainText('另一个会话的回复')
  finishOldRequest()
  await page.waitForTimeout(300)
  await expect(page.locator('.msgs')).not.toContainText('旧请求迟到的回复')
  await expect(page.getByRole('textbox', { name: '发送给客服的消息' })).toBeEnabled()
})
