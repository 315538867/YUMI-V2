import { expect, test } from '@playwright/test'
import { login } from './support'

test('归集失败、分摊与更正入口只调用成本命令且失败不假成功', async ({ page }) => {
  await login(page)
  await page.goto('/finance/cost-failures')
  await expect(page.getByRole('heading', { name: '成本归集失败' })).toBeVisible()

  await page.goto('/finance/cost-allocation')
  await page.getByRole('button', { name: '保存分摊' }).click()
  await expect(page.getByText('请输入成本事实标识')).toBeVisible()

  await page.goto('/finance/cost-adjustment')
  await page.getByRole('button', { name: '提交更正' }).click()
  await expect(page.getByText('请填写成本事实与更正原因')).toBeVisible()
})
