import { describe, expect, it, vi } from 'vitest'
import { cashService } from '@/modules/finance/cash/services/cashService'

const command = vi.fn()
const query = vi.fn()

vi.mock('@/platform/services/api', () => ({
  api: {
    query: (...args: unknown[]) => query(...args),
    command: (...args: unknown[]) => command(...args),
  },
}))

describe('现金 service', () => {
  it('日常收入不伪装成经营费用，金额以十进制对象传输', async () => {
    command.mockResolvedValue({})
    await cashService.recordDailyCash({
      direction: 'IN',
      categoryCode: 'DAILY_MISC',
      businessDate: '2026-10-20',
      amount: '50.00',
      description: '收入',
      countAsExpense: false,
    })
    const [, options] = command.mock.calls[0] as [string, Record<string, unknown>]
    const body = options.body as Record<string, unknown>
    expect(body.amount).toEqual({ amount: '50.00', currency: 'CNY' })
    expect(body.countAsExpense).toBe(false)
  })

  it('工资付款只提交 cash 路由', async () => {
    command.mockResolvedValue({})
    await cashService.payPayroll([{ resultBusinessId: 'PYR-1', amount: '100.00' }], '2026-10-20')
    const [path] = command.mock.calls[0] as [string]
    expect(path).toBe('/api/finance/cash/payroll-payments')
  })
})
