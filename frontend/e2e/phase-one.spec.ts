import { expect, test } from '@playwright/test'
import { login } from './support'

test('六模块、售后、设置与报表真实页面链路可操作', async ({ page }) => {
  await login(page)
  await expect(page.getByRole('heading', { name: '经营总览' })).toBeVisible()

  await page.goto('/customers')
  await page.getByRole('button', { name: '新增客户' }).click()
  const customerName = `闭环客户-${Date.now()}`
  const dialog = page.locator('.el-dialog')
  await dialog.locator('.el-form-item').filter({ hasText: '客户名称' }).locator('input').fill(customerName)
  await dialog.locator('.el-form-item').filter({ hasText: '联系人' }).locator('input').fill('闭环')
  await dialog.locator('.el-form-item').filter({ hasText: '电话' }).locator('input').fill('13900000003')
  await dialog.locator('.el-form-item').filter({ hasText: '默认地址' }).locator('textarea').fill('闭环路 1 号')
  await dialog.getByRole('button', { name: '创建' }).click()
  await expect(page.getByRole('heading', { name: customerName })).toBeVisible()

  await page.goto('/products')
  await expect(page.getByRole('heading', { name: '商品' })).toBeVisible()
  await page.goto('/workforce/employees')
  await expect(page.getByRole('heading', { name: '员工与资格' })).toBeVisible()
  await page.goto('/production/schedule')
  await expect(page.getByRole('button', { name: '安排任务' })).toBeVisible()
  await page.getByRole('tab', { name: '需求缺口' }).click()
  await expect(page.getByRole('heading', { name: '需求缺口' })).toBeVisible()

  await page.goto('/orders/new')
  await page.getByRole('button', { name: '确认建单' }).click()
  await expect(page).toHaveURL(/\/orders\/ORD-/)
  await page.getByRole('button', { name: '收款' }).click()
  await page.locator('.el-dialog .business-file-upload input[type="file"]').setInputFiles({
    name: 'voucher.pdf',
    mimeType: 'application/pdf',
    buffer: Buffer.from('phase-one-voucher'),
  })
  await expect(page.getByText('附件已上传')).toBeVisible()
  await page.locator('.el-dialog').getByRole('button', { name: '确认' }).click()
  await expect(page.getByText('已收').first()).toBeVisible()

  await page.goto('/after-sales')
  await expect(page.getByRole('button', { name: '受理售后' })).toBeVisible()
  await page.goto('/payroll/periods')
  await expect(page.getByRole('button', { name: '新建期间' })).toBeVisible()
  await page.goto('/finance/daily-cash')
  await expect(page.getByRole('heading', { name: '日常经营收支' })).toBeVisible()
  await page.goto('/reporting/monthly')
  await expect(page.getByText('数据时效')).toBeVisible()
  await page.goto('/settings/accounts')
  await expect(page.getByRole('heading', { name: '账号与角色' })).toBeVisible()
  await page.goto('/settings/tasks')
  await expect(page.getByRole('heading', { name: '提交后任务' })).toBeVisible()

  await page.setViewportSize({ width: 900, height: 800 })
  await page.goto('/customers')
  await expect(page.getByPlaceholder('名称、编号、联系人或电话')).toBeVisible()
  await expect(page.getByRole('button', { name: '查询' })).toBeVisible()
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - window.innerWidth,
  )
  expect(overflow).toBeLessThanOrEqual(1)

  const body = await page.locator('body').innerText()
  expect(body).not.toContain('{{')
  expect(body).not.toContain('undefined')
})
