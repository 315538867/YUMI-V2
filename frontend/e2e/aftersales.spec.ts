import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { authHeaders, login, loginViaApi } from './support'

interface PreparedOrder {
  orderBusinessId: string
  orderNo: string
  orderItemId: string
  groupId: string
  sourceId: string
  amount: string
}

function today(): string {
  return new Date().toISOString().slice(0, 10)
}

async function api<T>(
  request: APIRequestContext,
  token: string,
  method: 'get' | 'post' | 'put',
  path: string,
  data?: unknown,
  idempotencyKey?: string,
): Promise<T> {
  const response = await request[method](path, {
    headers: authHeaders(token, idempotencyKey),
    ...(data === undefined ? {} : { data }),
  })
  expect(response.ok(), `${method} ${path} -> ${response.status()} ${await response.text()}`).toBeTruthy()
  return (await response.json()) as T
}

async function prepareCompletedOrder(
  request: APIRequestContext,
  token: string,
): Promise<PreparedOrder> {
  const customers = await api<Array<{ customerBusinessId: string; contactName: string; phone: string; defaultAddress: string; status: string }>>(
    request, token, 'get', '/api/customers?page=1&size=10',
  )
  const products = await api<Array<{ productBusinessId: string }>>(request, token, 'get', '/api/products?page=1&size=10')
  const activeCustomers = customers.filter((item) => item.status === 'ACTIVE')
  expect(activeCustomers.length).toBeGreaterThan(0)
  expect(products.length).toBeGreaterThan(0)
  const customer = activeCustomers[0]!
  const product = products[0]!
  const schemes = await api<Array<{ schemeBusinessId: string; targetProcess: string; salePrice: { amount: string } }>>(
    request, token, 'get', `/api/products/${product.productBusinessId}/schemes`,
  )
  const scheme = schemes.find((item) => item.targetProcess === 'SEAM_CUTTING') ?? schemes[0]!
  const entry = await api<{ ref: unknown }>(
    request, token, 'get', `/api/products/schemes/${scheme.schemeBusinessId}/order-entry`,
  )
  const order = await api<{ orderBusinessId: string; orderNo: string }>(
    request, token, 'post', '/api/orders', {
      customerBusinessId: customer.customerBusinessId,
      contactOverride: null,
      orderDate: today(),
      expectedDeliveryDate: today(),
      items: [{
        productBusinessId: product.productBusinessId,
        schemeSnapshotRef: entry.ref,
        targetProcess: 'MAKING',
        quantity: 1,
        unitPrice: { amount: scheme.salePrice.amount, currency: 'CNY' },
        materialSources: { glue: 'STUDIO', colorPaste: 'STUDIO', bag: 'STUDIO', decoration: 'STUDIO' },
        recipient: {
          customerName: customer.contactName,
          phone: customer.phone,
          address: customer.defaultAddress,
        },
      }],
    }, `e2e-as-order-${Date.now()}`,
  )
  const detail = await api<{
    items: Array<{ orderItemBusinessId: string; agreement: { agreementGroupBusinessId: string } }>
  }>(request, token, 'get', `/api/orders/${order.orderBusinessId}`)
  const item = detail.items[0]!
  const trace = await api<{ demands: Array<{ productionSourceBusinessId: string }> }>(
    request, token, 'get', `/api/production/orders/${item.orderItemBusinessId}/trace`,
  )
  const sourceId = trace.demands[0]!.productionSourceBusinessId

  const employee = await api<{ employeeBusinessId: string }>(
    request, token, 'post', '/api/workforce/employees', {
      name: `售后工人-${Date.now()}`,
      phone: '13400000001',
      effectiveDate: today(),
    }, `e2e-as-emp-${Date.now()}`,
  )
  await api(request, token, 'post',
    `/api/workforce/employees/${employee.employeeBusinessId}/qualifications/grant`, {
      workTypeCode: 'MAKING',
      effectiveDate: today(),
    }, `e2e-as-qual-${Date.now()}`)
  const task = await api<{ taskBusinessId: string }>(
    request, token, 'post', '/api/production/tasks/arrangements', {
      taskType: 'NORMAL_PRODUCTION',
      subject: { subjectType: 'EMPLOYEE', subjectBusinessId: employee.employeeBusinessId },
      workDate: today(),
      processCode: 'MAKING',
      items: [{ productionSourceBusinessId: sourceId, plannedQuantity: 1 }],
      enteredStandardMinutes: null,
      otherTask: null,
      overrideReason: null,
    }, `e2e-as-task-${Date.now()}`,
  )
  const taskView = await api<{ items: Array<{ taskItemBusinessId: string }> }>(
    request, token, 'get', `/api/production/tasks/${task.taskBusinessId}`,
  )
  await api(request, token, 'post', '/api/production/tasks/verification', {
    items: [{
      taskItemBusinessId: taskView.items[0]!.taskItemBusinessId,
      unfinishedQuantity: 0,
      qualifiedQuantity: 1,
      reworkQuantity: 0,
      scrappedQuantity: 0,
      defects: [],
      resultNote: null,
    }],
  }, `e2e-as-verify-${Date.now()}`)
  await api(request, token, 'post', '/api/orders/delivery/shipments', {
    orderBusinessId: order.orderBusinessId,
    items: [{
      agreementGroupBusinessId: item.agreement.agreementGroupBusinessId,
      productionSourceBusinessId: sourceId,
      quantity: 1,
    }],
    trackingNo: `SF-${Date.now()}`,
    shippedAt: `${today()}T10:00:00`,
  }, `e2e-as-ship-${Date.now()}`)
  await api(request, token, 'post', '/api/finance/cash/order-receipts', {
    orderBusinessId: order.orderBusinessId,
    amount: { amount: scheme.salePrice.amount, currency: 'CNY' },
    businessDate: today(),
  }, `e2e-as-cash-${Date.now()}`)
  await api(request, token, 'post', `/api/orders/${order.orderBusinessId}/completion`, {}, `e2e-as-complete-${Date.now()}`)

  return {
    orderBusinessId: order.orderBusinessId,
    orderNo: order.orderNo,
    orderItemId: item.orderItemBusinessId,
    groupId: item.agreement.agreementGroupBusinessId,
    sourceId,
    amount: scheme.salePrice.amount,
  }
}

function dialogInput(page: Page, label: string) {
  return page.locator('.el-dialog .el-form-item').filter({ hasText: label }).locator('input, textarea').first()
}

test('售后受理、费用确认、退款与结案使用正式命令', async ({ page, request }) => {
  const token = await loginViaApi(request)
  const prepared = await prepareCompletedOrder(request, token)

  await login(page)
  await page.goto('/after-sales')
  await page.getByRole('button', { name: '受理售后' }).click()
  await dialogInput(page, '订单标识').fill(prepared.orderBusinessId)
  await dialogInput(page, '问题说明').fill('验收售后')
  await dialogInput(page, '目标金额').fill('20.00')
  await page.locator('.el-dialog').getByRole('button', { name: '确认' }).click()
  await expect(page.getByText(/售后案例已受理/).first()).toBeVisible()
  await expect(page.getByText('验收售后')).toBeVisible()

  await page.getByRole('button', { name: '确认费用' }).click()
  await dialogInput(page, '金额').fill('8.00')
  await page.locator('.el-dialog .el-form-item').filter({ hasText: '原因' }).locator('textarea').fill('售后费用')
  await page.locator('.el-dialog').getByRole('button', { name: '确认' }).click()
  await expect(page.getByText(/售后费用已确认/).first()).toBeVisible()
  await expect(page.getByText('售后费用（不属于现金）')).toBeVisible()
  await expect(page.locator('.aftersales-page .case-list').last().locator('tbody tr').first()).toBeVisible()

  await page.getByRole('button', { name: '赔付/退款' }).first().click()
  await dialogInput(page, '金额').fill('20.00')
  await page.locator('.el-dialog').getByRole('button', { name: '确认' }).click()
  await expect(page.getByText(/赔付\/退款已登记到现金/).first()).toBeVisible()

  await page.getByRole('button', { name: '结案' }).click()
  await page.locator('.el-dialog').getByRole('button', { name: '确认' }).click()
  await expect(page.getByText(/售后案例已结案/).first()).toBeVisible()
})
