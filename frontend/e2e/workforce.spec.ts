import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { authHeaders, login, loginViaApi } from './support'

async function hire(
  request: APIRequestContext,
  token: string,
  name: string,
): Promise<{ employeeBusinessId: string; rowVersion: number }> {
  const response = await request.post('/api/workforce/employees', {
    headers: authHeaders(token, `e2e-hire-${Date.now()}`),
    data: { name, phone: '13600000001', effectiveDate: '2026-10-01' },
  })
  expect(response.status()).toBe(201)
  return (await response.json()) as { employeeBusinessId: string; rowVersion: number }
}

function formInput(page: Page, label: string) {
  return page.locator('.el-form-item').filter({ hasText: label }).locator('input').first()
}

test('员工详情、资格、整页编辑与离职返聘使用真实接口', async ({ page, request }) => {
  const token = await loginViaApi(request)
  const name = `验收员工-${Date.now()}`
  const employee = await hire(request, token, name)

  await login(page)
  await page.goto('/workforce/employees')
  await page.getByLabel('姓名、编号或电话').fill(name)
  await page.getByRole('button', { name: '查询' }).click()
  const row = page.locator('.el-table__row').filter({ hasText: name })
  await expect(row).toBeVisible()
  await row.getByRole('button', { name: '查看' }).click()
  await expect(page).toHaveURL(new RegExp(`/workforce/employees/${employee.employeeBusinessId}$`))
  await expect(page.getByRole('heading', { name })).toBeVisible()

  await page.getByRole('button', { name: '工种资格' }).click()
  await page
    .locator('.el-dialog .el-form-item')
    .filter({ hasText: '工种' })
    .locator('.el-select')
    .click()
  await page.getByRole('option', { name: '制作' }).click()
  await page.locator('.el-dialog').getByRole('button', { name: '确认' }).click()
  await expect(
    page.locator('.qualification-table .el-table__row').filter({ hasText: '制作' }),
  ).toBeVisible()

  await page.getByRole('button', { name: '编辑资料' }).click()
  await expect(page).toHaveURL(
    new RegExp(`/workforce/employees/${employee.employeeBusinessId}/edit$`),
  )
  await expect(page.getByRole('heading', { name: '编辑员工' })).toBeVisible()
  await expect(formInput(page, '姓名')).toHaveValue(name)
  await formInput(page, '姓名').fill(`${name}-已改`)
  await expect(formInput(page, '姓名')).toHaveValue(`${name}-已改`)
  await page.getByRole('button', { name: '保存' }).click()
  await expect(page).toHaveURL(new RegExp(`/workforce/employees/${employee.employeeBusinessId}$`))
  await expect(page.getByRole('heading', { name: `${name}-已改` })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: `${name}-已改` })).toBeVisible()

  await page.getByRole('button', { name: '登记离职' }).click()
  await page.getByLabel('离职原因').fill('验收离职')
  await page.locator('.el-dialog').getByRole('button', { name: '确认' }).click()
  await expect(page.locator('.el-descriptions').getByText('离职', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: '登记返聘' }).click()
  await page.locator('.el-dialog').getByRole('button', { name: '确认' }).click()
  await expect(page.locator('.el-descriptions').getByText('在职', { exact: true })).toBeVisible()

  await page.locator('.history-tabs').getByRole('tab', { name: '就业记录' }).click()
  await expect(page.locator('.el-table__row').filter({ hasText: '返聘' }).first()).toBeVisible()
  await page.locator('.history-tabs').getByRole('tab', { name: '资格变更' }).click()
  await expect(page.locator('.el-table__row').filter({ hasText: '授予资格' }).first()).toBeVisible()

  await page.locator('.workforce-tabs').getByRole('tab', { name: '活动引用' }).click()
  await page
    .locator('.el-form-item')
    .filter({ hasText: '主体' })
    .locator('.el-select')
    .last()
    .click()
  await page.getByRole('option', { name: `${name}-已改` }).click()
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page.getByText(`${name}-已改`).last()).toBeVisible()
  expect(employee.employeeBusinessId).toBeTruthy()
})

test('小组详情、整页编辑与人员标准', async ({ page, request }) => {
  const token = await loginViaApi(request)
  const name = `组员-${Date.now()}`
  const secondName = `组员二-${Date.now()}`
  await hire(request, token, name)
  await hire(request, token, secondName)
  const teamName = `验收小组-${Date.now()}`

  await login(page)
  await page.goto('/workforce/teams')
  await page.getByRole('button', { name: '新增小组' }).click()
  await expect(page).toHaveURL(/\/workforce\/teams\/new$/)
  await expect(page.getByRole('heading', { name: '新增小组' })).toBeVisible()
  await page.getByLabel('小组名称').fill(teamName)
  await page.locator('.el-form-item').filter({ hasText: '成员' }).locator('.el-select').click()
  await page.getByRole('option', { name: new RegExp(name) }).click()
  await page.getByRole('option', { name: new RegExp(secondName) }).click()
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: '保存' }).click()
  await expect(page).toHaveURL(/\/workforce\/teams\/TEAM-/)
  await expect(page.getByRole('heading', { name: teamName })).toBeVisible()
  await expect(page.locator('.member-table .el-table__row').filter({ hasText: name })).toBeVisible()

  await page.getByRole('button', { name: '编辑小组' }).click()
  await expect(page).toHaveURL(/\/workforce\/teams\/[^/]+\/edit$/)
  await expect(page.getByRole('heading', { name: '编辑小组' })).toBeVisible()
  await expect(formInput(page, '小组名称')).toHaveValue(teamName)
  const renamed = `${teamName}-已改`
  await formInput(page, '小组名称').fill(renamed)
  await expect(formInput(page, '小组名称')).toHaveValue(renamed)
  await page.getByRole('button', { name: '保存' }).click()
  await expect(page).toHaveURL(/\/workforce\/teams\/[^/]+$/)
  await expect(page.getByRole('heading', { name: renamed })).toBeVisible()

  await page.locator('.history-tabs').getByRole('tab', { name: '成员变更' }).click()
  await expect(page.locator('.el-table__row').filter({ hasText: name }).first()).toBeVisible()
  await page.locator('.history-tabs').getByRole('tab', { name: '状态记录' }).click()
  await expect(page.getByText('创建').first()).toBeVisible()

  await page.locator('.workforce-tabs').getByRole('tab', { name: '人员标准' }).click()
  await expect(page.locator('.efficiency-table .el-table__row').first()).toBeVisible()
  const existingCounts = (
    await page.locator('.efficiency-table .el-table__row td:first-child').allInnerTexts()
  )
    .map((text) => Number(text.trim()))
    .filter((value) => Number.isFinite(value))
  const memberCount = Math.max(2, ...existingCounts) + 1
  await page.getByRole('button', { name: '新增人数标准' }).click()
  const standardDialog = page.locator('.el-dialog')
  await standardDialog
    .locator('.el-form-item')
    .filter({ hasText: '人数' })
    .locator('input')
    .fill(String(memberCount))
  await standardDialog
    .locator('.el-form-item')
    .filter({ hasText: '效率因子' })
    .locator('input')
    .fill('1.70')
  await standardDialog.getByRole('button', { name: '创建' }).click()
  const standardRow = page
    .locator('.efficiency-table .el-table__row')
    .filter({ hasText: String(memberCount) })
  await expect(standardRow).toBeVisible()
  await standardRow.getByRole('button', { name: '版本记录' }).click()
  await expect(page.getByRole('dialog').getByRole('heading', { name: /版本记录/ })).toBeVisible()
  await expect(page.locator('.el-dialog .el-table__row').first()).toBeVisible()
  await expect(page.locator('.el-dialog').getByText('V1')).toBeVisible()

  await expect(page.getByText('绑定账号')).toHaveCount(0)
  await expect(page.getByPlaceholder('登录名')).toHaveCount(0)
})

test('员工新增使用整页表单并展示资料预览', async ({ page }) => {
  await login(page)
  await page.goto('/workforce/employees/new')
  await expect(page.getByRole('heading', { name: '新增员工' })).toBeVisible()
  const name = `整页员工-${Date.now()}`
  await page.getByLabel('姓名').fill(name)
  await page.getByLabel('电话').fill('13600000009')
  await expect(page.getByText('员工资料预览')).toBeVisible()
  await expect(page.locator('.editor-page__preview').getByText(name)).toBeVisible()
  await page.getByRole('button', { name: '保存' }).click()
  await expect(page).toHaveURL(/\/workforce\/employees\/EMP-/)
  await expect(page.getByRole('heading', { name })).toBeVisible()
})
