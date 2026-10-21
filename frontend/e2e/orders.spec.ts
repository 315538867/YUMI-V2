import { expect, test, type Page } from '@playwright/test'
import { login } from './support'

function formInput(page: Page, label: string) {
  return page.locator('.el-form-item').filter({ hasText: label }).locator('input').first()
}

test('新订单确认、收款与刷新恢复使用真实接口', async ({ page }) => {
  await login(page)
  page.on('request', (request) => {
    if (request.url().endsWith('/api/orders') && request.method() === 'POST') {
      console.log('ORDER BODY', request.postData())
    }
  })
  await page.goto('/orders/new')
  await expect(page.getByRole('heading', { name: '新增订单' })).toBeVisible()

  await formInput(page, '订购数量').fill('2')
  await page.getByRole('button', { name: '确认建单' }).click()

  await expect(page).toHaveURL(/\/orders\/ORD-/)
  const orderNo = await page.locator('.order-detail h1').innerText()
  await expect(page.getByText('当前应收')).toBeVisible()

  await page.getByRole('button', { name: '收款' }).click()
  await page.locator('.el-dialog').getByRole('button', { name: '确认' }).click()
  await expect(page.getByText('收款已登记')).toBeVisible()
  await expect(page.getByText('已收')).toBeVisible()

  await page.reload()
  await expect(page.getByText('已收')).toBeVisible()
  await expect(page.locator('.order-detail h1')).toHaveText(orderNo)

  await page.goto('/orders')
  await page.getByPlaceholder('订单号或客户').fill(orderNo)
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page.getByRole('cell', { name: orderNo })).toBeVisible()
})

test('已有订单接管只记录当前阶段并可回查', async ({ page }) => {
  await login(page)
  await page.goto('/orders/opening')
  await formInput(page, '原订单参考').fill(`LEGACY-${Date.now()}`)
  await page.getByRole('button', { name: '确认接管' }).click()

  await expect(page).toHaveURL(/\/orders\/ORD-/)
  await expect(page.getByText('LEGACY_INTAKE')).toBeVisible()
})
