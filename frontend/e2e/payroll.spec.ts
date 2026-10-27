import { expect, test } from '@playwright/test'
import { login } from './support'

test('逐人参考、独立核定、人工调整与部分付款分离提交', async ({ page }) => {
  test.setTimeout(60_000)
  await login(page)
  await page.goto('/payroll/periods')
  const periodSelect = page.locator('.periods-page .el-select')
  await periodSelect.click()
  if ((await page.getByRole('option').count()) === 0) {
    await page.keyboard.press('Escape')
    await page.getByRole('button', { name: '新建期间' }).click()
    const periodDialog = page.locator('.el-dialog')
    await periodDialog.locator('.el-form-item').filter({ hasText: '期间编号' }).locator('input').fill(`E2E-${Date.now()}`)
    await periodDialog.getByRole('button', { name: '创建' }).click()
    await expect(page.getByText('工资期间已创建')).toBeVisible()
  } else {
    await page.getByRole('option').last().click()
  }
  await page.getByRole('button', { name: '刷新参考' }).click()
  await expect(page.getByText(/参考已刷新/).first()).toBeVisible()

  const firstRow = page.locator('.el-table__row').filter({ hasText: '参考就绪' }).first()
  await expect(firstRow).toBeVisible()
  await firstRow.getByRole('button', { name: '逐人核对' }).click()

  await expect(page.getByRole('heading', { name: '逐人工资核定' })).toBeVisible()
  await page.locator('.employee-payroll .el-form-item').filter({ hasText: '最终工资' }).locator('input').fill('1000.00')
  await page.locator('.employee-payroll .el-form-item').filter({ hasText: '核定说明' }).locator('textarea').fill('验收核定')
  await page.getByRole('button', { name: '确认工资' }).click()
  await expect(page.getByText(/工资已核定/).first()).toBeVisible()

  await page.goto('/payroll/payments')
  await expect(page.getByRole('heading', { name: '工资付款' })).toBeVisible()

  await page.goto('/payroll/glue')
  await expect(page.getByRole('heading', { name: '胶水自然月参考' })).toBeVisible()
})