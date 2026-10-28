import { expect, test } from '@playwright/test'

/**
 * 统一导航、认证守卫、错误处理与十进制金额契约。
 *
 * 数据只通过正式 HTTP 接口读取或准备，不直接写数据库；管理员由部署/测试环境预置。
 */
const ADMIN_LOGIN = process.env.YUMI_E2E_ADMIN_LOGIN ?? 'it-admin'
const ADMIN_PASSWORD = process.env.YUMI_E2E_ADMIN_PASSWORD ?? 'it-pass-1234'

async function login(page: import('@playwright/test').Page): Promise<void> {
  await page.goto('/login')
  await page.getByPlaceholder('登录名').fill(ADMIN_LOGIN)
  await page.getByPlaceholder('口令').fill(ADMIN_PASSWORD)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(
    /\/(customers|products|workforce|production|orders|after-sales|payroll|finance|reporting|settings)?$/,
  )
}

async function tokenOf(page: import('@playwright/test').Page): Promise<string> {
  const token = await page.evaluate(() => localStorage.getItem('yumi.session.token'))
  expect(token).toBeTruthy()
  return token as string
}

test('未登录访问业务路由被重定向到登录页', async ({ page }) => {
  await page.goto('/orders')

  await expect(page).toHaveURL(/\/login\?redirect=(%2F|\/)orders/)
  await expect(page.getByRole('heading', { name: 'YUMI 一期业务系统' })).toBeVisible()
})

test('登录后统一导航可进入业务模块', async ({ page }) => {
  await login(page)

  for (const label of [
    '客户',
    '商品',
    '人员与小组',
    '生产',
    '订单',
    '售后',
    '工资',
    '现金与成本',
    '报表',
  ]) {
    await expect(page.getByRole('menuitem', { name: label })).toBeVisible()
  }

  await page.getByRole('menuitem', { name: '客户' }).click()
  await expect(page).toHaveURL(/\/customers$/)
  await expect(page.getByRole('heading', { name: '客户', level: 1 })).toBeVisible()
})

test('金额契约保持十进制字符串，不经过浮点数', async ({ page, request }) => {
  await login(page)
  const token = await tokenOf(page)

  const response = await request.get('/api/products', {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(response.ok()).toBeTruthy()
  const products = (await response.json()) as Array<{
    standardSalePrice: { amount: string; currency: string }
  }>
  expect(products.length).toBeGreaterThan(0)
  expect(typeof products[0]?.standardSalePrice.amount).toBe('string')
  expect(products[0]?.standardSalePrice.amount).toMatch(/^\d+(\.\d+)?$/)
})

test('会话清除后受保护路由回到登录页', async ({ page }) => {
  await login(page)
  await page.evaluate(() => localStorage.removeItem('yumi.session.token'))
  await page.reload()
  await page.goto('/orders')

  await expect(page).toHaveURL(/\/login/)
})

test('上传失败返回明确错误且不伪装成功', async ({ page, request }) => {
  await login(page)
  const token = await tokenOf(page)

  const response = await request.post('/api/platform/files', {
    headers: {
      Authorization: `Bearer ${token}`,
      'Idempotency-Key': `e2e-upload-${Date.now()}`,
    },
    data: {
      originalName: 'broken.txt',
      mediaType: 'text/plain',
      contentBase64: '***not-base64***',
    },
  })

  expect(response.status()).toBe(400)
  const body = (await response.json()) as { type: string; message: string }
  expect(body.type).toBe('VALIDATION')
  expect(body.message.length).toBeGreaterThan(0)
})
