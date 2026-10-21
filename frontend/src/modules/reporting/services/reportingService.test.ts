import { describe, expect, it, vi } from 'vitest'
import { reportingService } from '@/modules/reporting/services/reportingService'

const query = vi.fn()

vi.mock('@/platform/services/api', () => ({
  api: {
    query: (...args: unknown[]) => query(...args),
    command: vi.fn(),
  },
}))

describe('报表 service', () => {
  it('月汇总明确区分现金净额与经营收入', async () => {
    query.mockResolvedValue({
      cashNet: '-240.00',
      operatingIncome: '50.00',
      payrollCashReturn: '100.00',
    })
    const summary = await reportingService.monthlySummary('2026-10')
    expect(query).toHaveBeenCalledWith('/api/reporting/monthly-summary?month=2026-10')
    expect(summary.cashNet).not.toBe(summary.operatingIncome)
  })

  it('时效查询读取九个来源水位', async () => {
    query.mockResolvedValue({ feeds: [] })
    await reportingService.freshness()
    expect(query).toHaveBeenCalledWith('/api/reporting/freshness')
  })
})
