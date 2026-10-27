import { expect, test, type APIRequestContext } from '@playwright/test'
import { authHeaders, login, loginViaApi } from './support'

interface Customer {
  customerBusinessId: string
  customerNo: string
  name: string
  rowVersion: number
  status: string
}

async function createCustomer(
  request: APIRequestContext,
  token: string,
  name: string,
): Promise<Customer> {
  const response = await request.post('/api/customers', {
    headers: authHeaders(token, `e2e-customer-${Date.now()}-${Math.random()}`),
    data: {
      name,
      contactName: '接口联系人',
      phone: '13900000001',
      defaultAddress: '上海市验收路 1 号',
    },
  })
  expect(response.status()).toBe(201)
  return (await response.json()) as Customer
}

test('客户列表、编辑、启停与历史引用使用真实接口', async ({ page, request }) => {
  const token = await loginViaApi(request)
  const suffix = Date.now()
  const name = `验收客户-${suffix}`
  const customer = await createCustomer(request, token, name)

  await login(page)
  await page.goto('/customers')
  await expect(page.getByText(/启用客户 \d+/)).toBeVisible()
  await page.getByPlaceholder('名称、编号、联系人或电话').fill(name)
  await page.getByRole('button', { name: '查询' }).click()

  await expect(page.getByRole('cell', { name, exact: true })).toBeVisible()
  await page.getByRole('button', { name: '查看' }).first().click()
  await expect(page).toHaveURL(new RegExp(`/customers/${customer.customerBusinessId}$`))
  await expect(page.getByRole('heading', { name })).toBeVisible()

  await page.getByRole('button', { name: '编辑资料' }).click()
  await expect(page).toHaveURL(new RegExp(`/customers/${customer.customerBusinessId}/edit$`))
  await expect(page.getByRole('heading', { name: '编辑客户' })).toBeVisible()
  const renamed = `${name}-已改`
  const nameInput = page.locator('.el-form-item').filter({ hasText: '客户名称' }).locator('input')
  await nameInput.fill(renamed)
  await page.getByRole('button', { name: '保存' }).click()
  await expect(page).toHaveURL(new RegExp(`/customers/${customer.customerBusinessId}$`))
  await expect(page.getByRole('heading', { name: renamed })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: renamed })).toBeVisible()

  await page.getByRole('button', { name: '停用客户' }).click()
  await expect(page.getByText('停用', { exact: true }).first()).toBeVisible()
  await page.reload()
  await expect(page.getByRole('button', { name: '启用客户' })).toBeVisible()

  await expect(page.getByText('资料变化历史')).toBeVisible()
  const historyRows = page.locator('.el-table__body-wrapper tbody tr')
  await expect(historyRows.first()).toBeVisible()
})

test('版本冲突保留输入并提示刷新，不静默覆盖', async ({ page, request }) => {
  const token = await loginViaApi(request)
  const name = `冲突客户-${Date.now()}`
  const customer = await createCustomer(request, token, name)

  await login(page)
  await page.goto(`/customers/${customer.customerBusinessId}`)
  await page.getByRole('button', { name: '编辑资料' }).click()
  await expect(page).toHaveURL(new RegExp(`/customers/${customer.customerBusinessId}/edit$`))

  const concurrent = await request.put(`/api/customers/${customer.customerBusinessId}`, {
    headers: authHeaders(token, `e2e-concurrent-${Date.now()}`),
    data: {
      name: `${name}-服务端`,
      contactName: '接口联系人',
      phone: '13900000001',
      defaultAddress: '上海市验收路 2 号',
      expectedRowVersion: customer.rowVersion,
    },
  })
  expect(concurrent.ok()).toBeTruthy()

  const nameInput = page.locator('.el-form-item').filter({ hasText: '客户名称' }).locator('input')
  await nameInput.fill(`${name}-页面`)
  await page.getByRole('button', { name: '保存' }).click()

  await expect(page.getByText(/资料已被其他人修改/)).toBeVisible()
  await expect(nameInput).toHaveValue(`${name}-页面`)
})

test('客户新建使用整页表单并展示资料预览', async ({ page }) => {
  await login(page)
  await page.goto('/customers/new')
  await expect(page.getByRole('heading', { name: '新建客户' })).toBeVisible()
  const name = `整页客户-${Date.now()}`
  await page.getByLabel('客户名称').fill(name)
  await page.getByLabel('联系人').fill('整页联系人')
  await page.getByLabel('电话').fill('13900000002')
  await page.getByLabel('默认地址').fill('上海市整页路 1 号')
  await expect(page.getByText('客户资料预览')).toBeVisible()
  await expect(page.locator('.preview').getByText(name)).toBeVisible()
  await page.getByRole('button', { name: '保存' }).click()
  await expect(page).toHaveURL(/\/customers\/CUS-/)
  await expect(page.getByRole('heading', { name })).toBeVisible()
})

test('编辑页未保存离开需要确认', async ({ page, request }) => {
  const token = await loginViaApi(request)
  const name = `离开确认客户-${Date.now()}`
  const customer = await createCustomer(request, token, name)

  await login(page)
  await page.goto(`/customers/${customer.customerBusinessId}/edit`)
  await expect(page.getByLabel('客户名称')).toHaveValue(name)
  await page.getByLabel('客户名称').fill(`${name}-未保存`)
  await page.getByRole('button', { name: '取消' }).click()

  const box = page.locator('.el-message-box')
  await expect(box.getByText('离开页面')).toBeVisible()
  await box.getByRole('button', { name: '继续编辑' }).click()
  await expect(page).toHaveURL(new RegExp(`/customers/${customer.customerBusinessId}/edit$`))

  await page.getByRole('button', { name: '取消' }).click()
  await box.getByRole('button', { name: '离开' }).click()
  await expect(page).toHaveURL(new RegExp(`/customers/${customer.customerBusinessId}$`))
})

test('新建客户字段校验内联展示且不离开页面', async ({ page }) => {
  await login(page)
  await page.goto('/customers/new')
  await page.getByRole('button', { name: '保存' }).click()
  await expect(page.getByText('请填写客户名称')).toBeVisible()
  await expect(page.getByText('请填写联系人')).toBeVisible()
  await expect(page).toHaveURL(/\/customers\/new$/)
})
