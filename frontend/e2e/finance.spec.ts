import { expect, test } from '@playwright/test'
import { login } from './support'

function formInput(page: import('@playwright/test').Page, label: string) {
  return page.locator('.el-form-item').filter({ hasText: label }).locator('input').first()
}

test('日常收支按业务日期归月并与现金流水一致', async ({ page }) => {
  await login(page)
  await page.goto('/finance/daily-cash')
  await page.locator('.el-form-item').filter({ hasText: '分类' }).locator('.el-select').click()
  await page.getByRole('option', { name: '日常杂项' }).click()
  await formInput(page, '金额').fill('50.00')
  await page
    .locator('.el-form-item')
    .filter({ hasText: '说明' })
    .locator('textarea')
    .fill('验收日常收入')
  await page.getByRole('button', { name: '登记收支' }).click()
  await expect(page.getByText(/已入账/).first()).toBeVisible()

  await page.goto('/finance/cash-history')
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page.getByRole('cell', { name: '日常收入' }).first()).toBeVisible()
  await expect(page.getByText('有效流入').first()).toBeVisible()
  await expect(page.getByText('现金净额').first()).toBeVisible()
})

test('成本明细与归集失败入口可只读查询', async ({ page }) => {
  await login(page)
  await page.goto('/finance/cost-entries')
  await page.getByPlaceholder('主体标识').fill('ORDER-NOT-FOUND')
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page.getByRole('heading', { name: '成本明细与汇总' })).toBeVisible()

  await page.goto('/finance/cost-failures')
  await expect(page.getByRole('heading', { name: '成本归集失败' })).toBeVisible()
})
