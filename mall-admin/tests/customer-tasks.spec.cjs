const { test, expect } = require('@playwright/test')

async function setup(page, overrides = {}) {
  const task = { id: 'task-1', runId: 'run-1', sessionId: 1, userId: 7, reason: 'sensitive_action', status: 'pending', version: 2,
    draft: '原始草稿', review: { reason: '需要人工确认' }, evidence: [{ source: '订单查询', status: '待支付' }],
    actionProposal: { kind: 'order_cancel', arguments: { order_id: 11 } }, actionStale: false,
    conversation: [{ id: 1, type: 'user', content: '帮我取消订单' }], ...overrides }
  const submitted = []
  await page.addInitScript(() => localStorage.setItem('admin', JSON.stringify({ user: { id: 1, name: '测试客服', role: 1 } })))
  await page.route('**/admin-api/**', async route => {
    const url = new URL(route.request().url())
    const path = url.pathname
    let data = {}
    if (path === '/admin-api/customer/tasks') data = { records: [task], total: 1, page: 1, size: 20 }
    else if (path.endsWith('/task-1/decision')) {
      const payload = route.request().postDataJSON()
      submitted.push(payload)
      task.status = 'decision_recorded'
      task.decision = payload
      data = task
    } else if (path.endsWith('/task-1')) data = task
    await route.fulfill({ json: { code: 200, message: '成功', data } })
  })
  await page.goto('/admin/customer')
  await page.getByRole('button', { name: '查看处理', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '客服回复', exact: true })).toHaveValue('原始草稿')
  return { task, submitted }
}

test('人工修改的回复按原文提交，回复操作不批准业务动作', async ({ page }) => {
  const { submitted } = await setup(page)
  const exactReply = '  人工确认：已收到申请。\n请保留外包装。  '
  await page.getByRole('textbox', { name: '客服回复', exact: true }).fill(exactReply)
  await page.locator('.el-drawer').screenshot({ path: 'test-results/customer/admin-edited-reply.png', animations: 'disabled' })
  await page.getByRole('button', { name: '确认并发送回复', exact: true }).click()
  await expect.poll(() => submitted.length).toBe(1)
  expect(submitted[0]).toMatchObject({ action: 'send_reply', reply: exactReply, version: 2 })
  expect(submitted[0].decisionId).toBeTruthy()
  await expect(page.getByRole('button', { name: '确认并发送回复', exact: true })).toBeDisabled()
})

test('客户补充信息使业务方案过期，禁止批准但仍可编写回复', async ({ page }) => {
  await setup(page, { actionStale: true })
  await expect(page.getByText('客户已补充信息，原业务方案需要重新核对。当前不能批准原方案，可以确认回复或要求客户补充信息。')).toBeVisible()
  await expect(page.getByRole('button', { name: '批准此业务操作', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '确认并发送回复', exact: true })).toBeEnabled()
  await page.locator('.el-drawer').screenshot({ path: 'test-results/customer/admin-stale-action.png', animations: 'disabled' })
})

test('业务批准需要确认，提交时不把编辑回复作为批准结果', async ({ page }) => {
  const { submitted } = await setup(page)
  await page.getByRole('button', { name: '批准此业务操作', exact: true }).click()
  await page.getByRole('button', { name: '确认批准', exact: true }).click()
  await expect.poll(() => submitted.length).toBe(1)
  expect(submitted[0].action).toBe('approve_action')
  expect(submitted[0]).not.toHaveProperty('reply')
})
