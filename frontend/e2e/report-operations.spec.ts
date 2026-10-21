import { expect, test, type APIRequestContext } from '@playwright/test'
import { authHeaders, login, loginViaApi } from './support'

async function createReader(request: APIRequestContext, adminToken: string) {
  const suffix = Date.now()
  const roleCode = `REPORT_READ_${suffix}`
  await request.post('/api/platform/authorization/roles', {
    headers: authHeaders(adminToken, `e2e-report-role-${suffix}`),
    data: {
      roleBusinessId: null,
      roleCode,
      name: '报表只读',
      permissionCodes: ['reporting.read'],
      expectedRowVersion: 0,
    },
  })
  const accountResponse = await request.post('/api/platform/identity/accounts', {
    headers: authHeaders(adminToken, `e2e-report-account-${suffix}`),
    data: {
      loginName: `reporter${suffix}`,
      initialPassword: 'reporter-pass-1',
      displayName: '报表只读',
    },
  })
  const account = (await accountResponse.json()) as {
    accountBusinessId: string
    rowVersion: number
  }
  const assign = await request.put(
    `/api/platform/authorization/accounts/${account.accountBusinessId}/roles`,
    {
      headers: authHeaders(adminToken, `e2e-report-assign-${suffix}`),
      data: { roleCodes: [roleCode], expectedAccountRowVersion: account.rowVersion },
    },
  )
  expect(assign.ok()).toBeTruthy()
  return { loginName: `reporter${suffix}`, password: 'reporter-pass-1' }
}

test('管理员可归集与影子重建，普通读取者不能触发', async ({ page, request }) => {
  const adminToken = await loginViaApi(request)
  const reader = await createReader(request, adminToken)

  await login(page)
  await page.goto('/reporting/rebuild')
  await page.getByRole('button', { name: '归集来源' }).click()
  await expect(page.getByText(/归集完成/).first()).toBeVisible()
  await page.getByRole('button', { name: '发起影子重建' }).click()
  await expect(page.getByText(/影子版本|重建失败保留旧投影/).first()).toBeVisible()
  await expect(page.locator('.el-table__row').first()).toBeVisible()

  await page.goto('/reporting/reconciliation')
  await expect(page.getByRole('heading', { name: '来源对账' })).toBeVisible()

  const readerLogin = await request.post('/api/platform/identity/sessions', {
    data: reader,
  })
  expect(readerLogin.ok()).toBeTruthy()
  const readerToken = ((await readerLogin.json()) as { token: string }).token
  const denied = await request.post('/api/reporting/administration/rebuilds', {
    headers: authHeaders(readerToken, `e2e-report-denied-${Date.now()}`),
    data: {},
  })
  expect(denied.status()).toBe(403)

  await page.getByRole('button', { name: '退出' }).click()
  await expect(page).toHaveURL(/\/login/)
  await page.getByPlaceholder('登录名').fill(reader.loginName)
  await page.getByPlaceholder('口令').fill(reader.password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/$/)
  await page.goto('/reporting/rebuild')
  await expect(page).toHaveURL(/\/forbidden$/)
})
