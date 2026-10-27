import { expect, test, type Page } from '@playwright/test'
import { login } from './support'

test.use({ viewport: { width: 1280, height: 800 }, reducedMotion: 'reduce' })

/** 截图基线只覆盖稳定页面与空态，避免业务数据和时间造成漂移。 */
async function freezeAnimations(page: Page): Promise<void> {
  await page.addStyleTag({
    content:
      '*, *::before, *::after { animation: none !important; transition: none !important; caret-color: transparent !important; }',
  })
}

async function expectNoMatch(page: Page, keyword: string, emptyText: string): Promise<void> {
  const input = page.getByPlaceholder(keyword)
  await input.fill('VISUAL-NO-MATCH')
  await input.press('Enter')
  await expect(page.getByText(emptyText)).toBeVisible()
}

test('登录页视觉基线', async ({ page }) => {
  await page.goto('/login')
  await expect(page.getByRole('button', { name: '登录' })).toBeVisible()
  await freezeAnimations(page)
  await expect(page).toHaveScreenshot('login.png', { maxDiffPixelRatio: 0.01 })
})

test('经营总览视觉基线', async ({ page }) => {
  await login(page)
  await expect(page.getByRole('heading', { name: '经营总览' })).toBeVisible()
  await freezeAnimations(page)
  await expect(page).toHaveScreenshot('home.png', { maxDiffPixelRatio: 0.01 })
})

test('订单空态视觉基线', async ({ page }) => {
  await login(page)
  await page.goto('/orders')
  await expectNoMatch(page, '订单号或客户', '没有匹配的订单')
  await freezeAnimations(page)
  await expect(page).toHaveScreenshot('orders-empty.png', { maxDiffPixelRatio: 0.01 })
})

test('商品空态视觉基线', async ({ page }) => {
  await login(page)
  await page.goto('/products')
  await expectNoMatch(page, '商品名称或编号', '没有匹配的商品')
  await freezeAnimations(page)
  await expect(page).toHaveScreenshot('products-empty.png', { maxDiffPixelRatio: 0.01 })
})
