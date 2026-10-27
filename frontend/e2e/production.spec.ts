import { expect, test, type Page } from '@playwright/test'
import { authHeaders, login, loginViaApi } from './support'

function formInput(page: Page, label: string) {
  return page.locator('.el-form-item').filter({ hasText: label }).locator('input').first()
}

test('无生产来源的其他任务可安排并核验', async ({ page, request }) => {
  const token = await loginViaApi(request)
  const worker = `其他任务员工-${Date.now()}`
  const hired = await request.post('/api/workforce/employees', {
    headers: authHeaders(token, `e2e-other-${Date.now()}`),
    data: { name: worker, phone: '13500000001', effectiveDate: '2026-10-01' },
  })
  expect(hired.status()).toBe(201)
  const employeeId = ((await hired.json()) as { employeeBusinessId: string }).employeeBusinessId
  await login(page)
  await page.goto('/production/schedule')
  await page.getByRole('button', { name: '安排任务' }).click()

  const dialog = page.locator('.el-dialog')
  await dialog
    .locator('.el-form-item')
    .filter({ hasText: '执行员工' })
    .locator('.el-select')
    .click()
  await page.getByRole('option', { name: new RegExp(worker) }).click()
  await page.keyboard.press('Escape')
  await dialog
    .locator('.el-form-item')
    .filter({ hasText: '工作内容' })
    .locator('textarea')
    .fill('验收其他任务')
  await formInput(page, '整体标准分钟').fill('45')
  await dialog.getByRole('button', { name: '保存' }).click()

  await expect(page.getByText('任务已安排')).toBeVisible()
  const row = page.locator('.el-table__row').filter({ hasText: employeeId })
  await expect(row).toBeVisible()
  await row.getByRole('button', { name: '核验' }).click()

  await expect(page.getByRole('heading', { name: '任务核验' })).toBeVisible()
  await page.getByRole('button', { name: '提交最终核验' }).click()
  await expect(page.getByText(/核验已提交/).first()).toBeVisible()

  const secondName = `取消任务员工-${Date.now()}`
  const secondHired = await request.post('/api/workforce/employees', {
    headers: authHeaders(token, `e2e-cancel-${Date.now()}`),
    data: { name: secondName, phone: '13500000002', effectiveDate: '2026-10-01' },
  })
  const secondId = ((await secondHired.json()) as { employeeBusinessId: string }).employeeBusinessId
  await page.goto('/production/schedule')
  await page.getByRole('button', { name: '安排任务' }).click()
  const cancelDialog = page.locator('.el-dialog')
  await cancelDialog
    .locator('.el-form-item')
    .filter({ hasText: '执行员工' })
    .locator('.el-select')
    .click()
  await page.getByRole('option', { name: new RegExp(secondName) }).click()
  await page.keyboard.press('Escape')
  await cancelDialog
    .locator('.el-form-item')
    .filter({ hasText: '工作内容' })
    .locator('textarea')
    .fill('待取消任务')
  await formInput(page, '整体标准分钟').fill('30')
  await cancelDialog.getByRole('button', { name: '保存' }).click()
  await expect(page.getByText('任务已安排')).toBeVisible()
  const cancelRow = page.locator('.el-table__row').filter({ hasText: secondId })
  await cancelRow.getByRole('button', { name: '取消' }).click()
  await page.locator('.el-message-box input').fill('接口验收取消')
  await page.locator('.el-message-box__btns .el-button--primary').click()
  await expect(page.getByText('任务已取消；未核验计划占用已释放').first()).toBeVisible()
  await expect(cancelRow.getByText('已取消')).toBeVisible()
})

test('独立调整其他任务的工作内容与标准分钟', async ({ page, request }) => {
  const token = await loginViaApi(request)
  const worker = `调整任务员工-${Date.now()}`
  const hired = await request.post('/api/workforce/employees', {
    headers: authHeaders(token, `e2e-adjust-hire-${Date.now()}`),
    data: { name: worker, phone: '13500000003', effectiveDate: '2026-10-01' },
  })
  expect(hired.status()).toBe(201)
  const employeeId = ((await hired.json()) as { employeeBusinessId: string }).employeeBusinessId
  const arranged = await request.post('/api/production/tasks/arrangements', {
    headers: authHeaders(token, `e2e-adjust-arrange-${Date.now()}`),
    data: {
      taskType: 'OTHER_TASK',
      subject: { subjectType: 'EMPLOYEE', subjectBusinessId: employeeId },
      workDate: '2026-10-11',
      processCode: null,
      items: [],
      enteredStandardMinutes: 30,
      otherTask: { workContent: '待调整其他任务' },
      overrideReason: null,
    },
  })
  expect(arranged.status()).toBe(201)
  const taskId = ((await arranged.json()) as { taskBusinessId: string }).taskBusinessId

  await login(page)
  await page.goto(`/production/tasks/${taskId}/adjust`)
  await expect(page.getByRole('heading', { name: /调整任务/ })).toBeVisible()
  await page
    .locator('.el-form-item')
    .filter({ hasText: '工作内容' })
    .locator('textarea')
    .fill('调整后的其他任务')
  await formInput(page, '整体标准分钟').fill('75')
  await page.getByRole('button', { name: '保存调整' }).click()

  await expect(page.getByText('任务已调整')).toBeVisible()
  await expect(page).toHaveURL(new RegExp(`/production/tasks/${taskId}$`))
  await expect(page.getByText('4500')).toBeVisible()
  await expect(page.getByText('调整后的其他任务')).toBeVisible()
})

test('缺陷原因维护与需求缺口分列展示', async ({ page }) => {
  await login(page)
  await page.goto('/production/defect-reasons')
  await page.getByRole('button', { name: '新增原因' }).click()
  const dialog = page.locator('.el-dialog')
  const code = `E2E_${Date.now()}`
  await dialog.locator('.el-form-item').filter({ hasText: '代码' }).locator('input').fill(code)
  await dialog
    .locator('.el-form-item')
    .filter({ hasText: '名称' })
    .locator('input')
    .fill('验收缺陷')
  await dialog.getByRole('button', { name: '保存' }).click()
  await expect(page.locator('.el-table__row').filter({ hasText: code })).toBeVisible()

  await page.getByRole('tab', { name: '需求缺口' }).click()
  const productSelect = page.locator('.gap-page .el-select')
  await productSelect.click()
  await page.getByRole('option').first().click()
  await expect(page.locator('.el-table__header').filter({ hasText: '实际销毁' })).toBeVisible()
  await expect(page.locator('.el-table__header').filter({ hasText: '转线下' })).toBeVisible()
})
