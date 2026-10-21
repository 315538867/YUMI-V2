import { describe, expect, it, vi } from 'vitest'
import { afterSalesService } from '@/modules/orders/aftersales/services/afterSalesService'

const command = vi.fn()

vi.mock('@/platform/services/api', () => ({
  api: {
    query: vi.fn(),
    command: (...args: unknown[]) => command(...args),
  },
}))

describe('售后 service', () => {
  it('池内处置只提交 orders 的 disposeReturnedProduct 路由', async () => {
    command.mockResolvedValue({})
    await afterSalesService.disposeReturnedProduct({
      receiptItemBusinessId: 'RCP-1',
      quantity: 1,
      dispositionType: 'SCRAPPED',
      physicalDestination: 'DESTROYED',
      businessDate: '2026-10-20',
      reasonText: '报废',
      responsibilityType: 'STUDIO',
      responsibleSubjectBusinessId: 'STUDIO',
      waivedReason: null,
      offlineInventoryName: null,
      offlineInventoryNote: null,
      handedOverBy: null,
      receivedBy: null,
      transferredAt: null,
    })
    const [path] = command.mock.calls[0] as [string]
    expect(path).toBe('/api/after-sales/returns/dispositions')
    expect(path).not.toContain('/production')
  })

  it('赔付只调用 cash 路由，费用确认留在售后', async () => {
    command.mockResolvedValue({})
    await afterSalesService.confirmCost({
      caseBusinessId: 'CASE-1',
      costCategory: 'AFTER_SALES_GOODWILL',
      amount: '8.00',
      businessDate: '2026-10-20',
      reasonText: '费用',
    })
    const [path] = command.mock.calls[0] as [string]
    expect(path).toBe('/api/after-sales/costs/confirmations')
  })
})
