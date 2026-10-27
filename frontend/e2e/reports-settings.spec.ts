import { expect, test, type APIRequestContext } from '@playwright/test'
import { authHeaders, login, loginViaApi } from './support'

async function prepareAccount(request: APIRequestContext, token: string) {
  const suffix = Date.now()
  const roleCode = `READER_${suffix}`
  const roleName = `验收只读-${suffix}`
  await request.post('/api/platform/authorization/roles', {
    headers: authHeaders(token, `e2e-role-${suffix}`),
    data: {
      roleBusinessId: null,
      roleCode,
      name: roleName,
      permissionCodes: ['reporting.read'],
      expectedRowVersion: 0,
    },
  })
  const response = await request.post('/api/platform/identity/accounts', {
    headers: authHeaders(token, `e2e-account-${suffix}`),
    data: {
      loginName: `reader${suffix}`,
      initialPassword: 'reader-pass-1',
      displayName: '验收只读账号',
    },
  })
  expect(response.status()).toBe(201)
  return {
    roleCode,
    roleName,
    loginName: `reader${suffix}`,
    account: (await response.json()) as { accountBusinessId: string; rowVersion: number },
  }
}

test('月汇总现金成本分列并显示来源延迟', async ({ page }) => {
  await login(page)
  await page.goto('/reporting')
  await expect(page.getByText('数据时效')).toBeVisible()
  await expect(page.getByText('净额', { exact: false }).first()).toBeVisible()
  await expect(page.getByText('数据时效')).toBeVisible()
})

test('账号角色维护与停用即时生效，胶水设置仍归工资', async ({ page, request }) => {
  const token = await loginViaApi(request)
  const prepared = await prepareAccount(request, token)

  await login(page)
  await page.goto('/settings/accounts')
  const row = page.locator('.el-table__row').filter({ hasText: prepared.loginName })
  await expect(row).toBeVisible()
  await row.getByRole('button', { name: '授权角色' }).click()
  await page.locator('.el-dialog .el-select').click()
  await page.getByRole('option', { name: prepared.roleName }).click()
  await page.keyboard.press('Escape')
  await page.locator('.el-dialog').getByRole('button', { name: '保存授权' }).click()
  await expect(row.getByText(prepared.roleName)).toBeVisible()

  await row.getByRole('button', { name: '停用' }).click()
  await expect(page.getByText('账号已停用，立即生效').first()).toBeVisible()
  await expect(row.getByRole('button', { name: '启用' })).toBeVisible()

  const loginAttempt = await request.post('/api/platform/identity/sessions', {
    data: { loginName: prepared.loginName, password: 'reader-pass-1' },
  })
  expect(loginAttempt.status()).toBe(403)

  await page.goto('/settings/glue')
  await expect(page.getByRole('heading', { name: '胶水减免设置' })).toBeVisible()
})
