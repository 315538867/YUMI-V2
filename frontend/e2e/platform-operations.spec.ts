import { expect, test } from '@playwright/test'
import { authHeaders, login, loginViaApi } from './support'

test('审计按目标检索且不可编辑，任务恢复仅管理员可用', async ({ page, request }) => {
  const token = await loginViaApi(request)
  const employeeResponse = await request.post('/api/workforce/employees', {
    headers: authHeaders(token, `e2e-audit-${Date.now()}`),
    data: {
      name: `审计员工-${Date.now()}`,
      phone: '13900000002',
      effectiveDate: new Date().toISOString().slice(0, 10),
    },
  })
  expect(employeeResponse.status()).toBe(201)
  const employee = (await employeeResponse.json()) as { employeeBusinessId: string }

  await login(page)
  await page.goto('/settings/audit')
  await page.locator('.audit-page .el-select').click()
  await page.getByRole('option', { name: '人员管理' }).click()
  await page.getByPlaceholder('目标业务标识').fill(employee.employeeBusinessId)
  await page.getByRole('button', { name: '检索' }).click()
  await expect(page.locator('.el-table__row').first()).toBeVisible()
  await expect(page.getByRole('button', { name: '删除' })).toHaveCount(0)

  await page.goto('/settings/tasks')
  await expect(page.getByRole('heading', { name: '提交后任务' })).toBeVisible()
  const unauthorized = await request.get('/api/platform/tasks?status=DEAD', {
    headers: { Authorization: '' },
  })
  expect(unauthorized.status()).toBe(403)
})
