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
  await page.getByRole('dialog').getByRole('button', { name: '确认建单' }).click()

  await expect(page).toHaveURL(/\/orders\/ORD-/)
  const orderNo = await page.locator('.order-detail h1').innerText()
  await expect(page.getByText('当前应收')).toBeVisible()

  await page.getByRole('button', { name: '登记收款' }).click()
  await page.getByRole('button', { name: '核对本次收款' }).click()
  await expect(
    page.getByRole('dialog').filter({ hasText: '确认订单实际收款' }),
  ).toBeVisible()
  await page.getByRole('button', { name: '确认已收到款项' }).click()
  await expect(page.getByText('收款已登记')).toBeVisible()
  await expect(page.getByRole('cell', { name: '已收', exact: true })).toBeVisible()

  await page.reload()
  await expect(page.getByRole('cell', { name: '已收', exact: true })).toBeVisible()
  await expect(page.locator('.order-detail h1')).toHaveText(orderNo)

  await page.goto('/orders')
  await page.getByPlaceholder('订单号或客户').fill(orderNo)
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page.getByRole('cell', { name: orderNo })).toBeVisible()
})

test('新建订单支持多明细并在详情五页签展示', async ({ page }) => {
  await login(page)
  await page.goto('/orders/new')
  await page.getByRole('button', { name: '新增明细' }).click()

  const cards = page.locator('.item-card')
  await expect(cards).toHaveCount(2)
  for (const card of [cards.nth(0), cards.nth(1)]) {
    await card.locator('.el-form-item').filter({ hasText: '订购数量' }).locator('input').fill('1')
  }

  await page.getByRole('button', { name: '确认建单' }).click()
  const preview = page.getByRole('dialog')
  await expect(preview.locator('.preview-table .el-table__row')).toHaveCount(2)
  await preview.getByRole('button', { name: '确认建单' }).click()
  await expect(page).toHaveURL(/\/orders\/ORD-/)

  for (const tab of ['成交约定', '生产进度', '发货记录', '收退款记录', '订单记录']) {
    await expect(page.getByRole('tab', { name: tab, exact: true })).toBeVisible()
  }
  await expect(page.locator('.tabs .el-table__row:visible')).toHaveCount(2)
})

test('已有订单接管只记录当前阶段并可回查', async ({ page }) => {
  await login(page)
  await page.goto('/orders/opening')
  await formInput(page, '原订单参考').fill(`LEGACY-${Date.now()}`)
  await page.getByRole('button', { name: '确认接管' }).click()

  await expect(page).toHaveURL(/\/orders\/ORD-/)
  await expect(page.getByText('已有订单接管')).toBeVisible()
})
