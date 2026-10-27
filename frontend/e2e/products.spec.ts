import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { authHeaders, login, loginViaApi } from './support'

function amount(value: string) {
  return { amount: value, currency: 'CNY' }
}

function productBody(name: string) {
  const one = amount('1.00')
  return {
    name,
    description: 'E2E 商品',
    standardWeightGrams: '120.0000',
    makingStarCode: '4',
    makingStandardMinutes: 30,
    standardSalePrice: amount('50.00'),
    glueLossRate: '0.0500',
    glueUnitPrice: one,
    colorPasteUnitPrice: one,
    makingCommission: one,
    mouldCount: 4,
    mouldDailyBatches: 10,
    makingOtherMaterialCost: one,
    baggingStandardMinutes: 5,
    baggingLaborCost: one,
    baggingBagUnitCost: one,
    baggingDecorationUnitPrice: one,
    baggingOtherCost: one,
    seamStandardMinutes: 4,
    seamLaborCost: one,
    seamCombinedMaterialCost: one,
    seamOtherCost: one,
    extraOtherTaskLaborCost: one,
    extraTransportPackagingCost: one,
    extraDailyMiscCost: one,
    extraRentUtilitiesCost: one,
    extraMouldAmortizationCost: one,
    extraOtherCost: one,
  }
}

async function createProduct(
  request: APIRequestContext,
  token: string,
  name: string,
): Promise<{ productBusinessId: string; contentVersion: number }> {
  const response = await request.post('/api/products', {
    headers: authHeaders(token, `e2e-product-${Date.now()}`),
    data: productBody(name),
  })
  expect(response.status()).toBe(201)
  return (await response.json()) as { productBusinessId: string; contentVersion: number }
}

function formInput(page: Page, label: string) {
  return page.locator('.el-form-item').filter({ hasText: label }).locator('input').first()
}

test('商品列表、详情与编辑保留标准售价且不出现商品目标工序', async ({ page, request }) => {
  const token = await loginViaApi(request)
  const name = `验收商品-${Date.now()}`
  const product = await createProduct(request, token, name)

  await login(page)
  await page.goto('/products')
  await page.getByPlaceholder('商品名称或编号').fill(name)
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page.getByRole('cell', { name, exact: true })).toBeVisible()
  await page.getByRole('button', { name: '查看' }).first().click()
  await expect(page.getByRole('heading', { name })).toBeVisible()
  await expect(page.getByText('50.00 CNY').first()).toBeVisible()

  await page.getByRole('button', { name: '编辑商品' }).click()
  await expect(page.getByRole('heading', { name: '编辑商品' })).toBeVisible()
  await expect(page.getByText('商品目标工序')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '预算预览' })).toBeVisible()
  await expect(page.getByText('全资源成本')).toBeVisible()
  await formInput(page, '标准售价').fill('66.00')
  await page.getByRole('button', { name: '保存商品' }).click()
  await expect(page.getByRole('heading', { name })).toBeVisible()
  await page.reload()
  await expect(page.getByText('66.00 CNY').first()).toBeVisible()
  await expect(page).toHaveURL(new RegExp(`/products/${product.productBusinessId}$`))
})

test('录入默认值与标准时间由商品设置维护', async ({ page }) => {
  await login(page)
  await page.goto('/products')
  await page.getByRole('button', { name: '录入设置' }).click()
  await expect(page.getByRole('heading', { name: '商品录入设置' })).toBeVisible()

  await formInput(page, '胶水损耗率').fill('0.0600')
  await page.getByRole('button', { name: '保存默认值' }).click()
  await expect(page.getByText('录入默认值已保存')).toBeVisible()
  await page.reload()
  await expect(formInput(page, '胶水损耗率')).toHaveValue('0.06000000')

  await expect(page.getByText('制作星级选项')).toBeVisible()
  await expect(page.getByText('工序标准时间选项')).toBeVisible()
})

test('自定义方案保存后由后端返回预算', async ({ page, request }) => {
  const token = await loginViaApi(request)
  const name = `方案商品-${Date.now()}`
  const product = await createProduct(request, token, name)

  await login(page)
  await page.goto(`/products/${product.productBusinessId}`)
  await page.getByRole('button', { name: '新建自定义方案' }).click()
  await formInput(page, '方案名称').fill('客户供料方案')
  await formInput(page, '销售价').fill('60.00')
  await expect(page.getByRole('heading', { name: '预算预览' })).toBeVisible()
  await expect(page.getByText('全资源成本')).toBeVisible()
  const glueSource = page.locator('.el-form-item').filter({ hasText: '胶水供料方' })
  await glueSource.locator('.el-select').click()
  await page.getByRole('option', { name: '客户' }).click()
  await page.getByRole('button', { name: '保存方案' }).click()

  await expect(page.getByRole('heading', { name })).toBeVisible()
  await expect(page.getByRole('cell', { name: '客户供料方案' })).toBeVisible()
  const schemeRow = page.locator('.el-table__row').filter({ hasText: '客户供料方案' })
  await expect(schemeRow.getByText('60.00')).toBeVisible()
  await expect(schemeRow.locator('td').nth(4)).not.toHaveText('—')
})
