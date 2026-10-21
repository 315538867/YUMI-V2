import { expect, test } from '@playwright/test'

test.describe('工程骨架', () => {
  test('应用外壳可加载并渲染首页', async ({ page }) => {
    await page.goto('/')

    await expect(page.locator('.yumi-app')).toBeVisible()
    await expect(page.getByRole('heading', { name: 'YUMI 一期业务系统' })).toBeVisible()
  })
})
