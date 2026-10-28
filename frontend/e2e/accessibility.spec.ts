import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { login } from './support'

const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']

const BUSINESS_PAGES = [
  ['经营总览', '/'],
  ['客户', '/customers'],
  ['商品', '/products'],
  ['人员与小组', '/workforce'],
  ['生产', '/production'],
  ['订单', '/orders'],
  ['售后', '/after-sales'],
  ['工资', '/payroll'],
  ['现金与成本', '/finance'],
  ['报表', '/reporting'],
  ['设置与账号', '/settings'],
] as const

const FORM_PAGES = [
  ['新建客户', '/customers/new'],
  ['新增员工', '/workforce/employees/new'],
  ['新增小组', '/workforce/teams/new'],
  ['新增商品', '/products/new'],
] as const

function seriousViolations(results: Awaited<ReturnType<AxeBuilder['analyze']>>) {
  return results.violations.filter(
    (violation) => violation.impact === 'serious' || violation.impact === 'critical',
  )
}

test.describe('无障碍门禁', () => {
  test('登录页无严重无障碍问题', async ({ page }) => {
    await page.goto('/login')
    await expect(page.getByRole('button', { name: '登录' })).toBeVisible()
    const violations = seriousViolations(await new AxeBuilder({ page }).withTags(TAGS).analyze())
    expect(violations, JSON.stringify(violations, null, 2)).toEqual([])
  })

  test('新建表单无严重无障碍问题', async ({ page }) => {
    await login(page)
    const failures: string[] = []
    for (const [name, path] of FORM_PAGES) {
      await page.goto(path)
      await expect(page.getByRole('heading', { name })).toBeVisible()
      await page.waitForLoadState('networkidle')
      const violations = seriousViolations(await new AxeBuilder({ page }).withTags(TAGS).analyze())
      for (const violation of violations) {
        const targets = violation.nodes.map((node) => node.target.join(' ')).join(' | ')
        failures.push(`${name} ${violation.id}: ${targets}`)
      }
    }
    expect(failures, failures.join('\n')).toEqual([])
  })

  test('离开确认弹窗具备可访问名称与操作按钮', async ({ page }) => {
    await login(page)
    await page.goto('/customers/new')
    await page.getByLabel('客户名称').fill('无障碍检查')
    await page.getByRole('button', { name: '取消' }).click()
    const dialog = page.getByRole('dialog', { name: '离开页面' })
    await expect(dialog).toBeVisible()
    await expect(dialog.getByRole('button', { name: '继续编辑' })).toBeVisible()
    await expect(dialog.getByRole('button', { name: '离开' })).toBeVisible()
    await dialog.getByRole('button', { name: '继续编辑' }).click()
  })

  test('主要业务页无严重无障碍问题', async ({ page }) => {
    await login(page)
    const failures: string[] = []
    for (const [name, path] of BUSINESS_PAGES) {
      await page.goto(path)
      await expect(page.locator('.breadcrumb strong')).toBeVisible()
      await page.waitForLoadState('networkidle')
      const violations = seriousViolations(await new AxeBuilder({ page }).withTags(TAGS).analyze())
      for (const violation of violations) {
        const targets = violation.nodes.map((node) => node.target.join(' ')).join(' | ')
        failures.push(`${name} ${violation.id}: ${targets}`)
      }
    }
    expect(failures, failures.join('\n')).toEqual([])
  })
})
