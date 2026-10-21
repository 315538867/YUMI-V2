import { describe, expect, it, vi } from 'vitest'
import { payrollService } from '@/modules/finance/payroll/services/payrollService'

const command = vi.fn()
const query = vi.fn()

vi.mock('@/platform/services/api', () => ({
  api: {
    query: (...args: unknown[]) => query(...args),
    command: (...args: unknown[]) => command(...args),
  },
}))

describe('工资 service', () => {
  it('人工处理金额可空，且不进入自动扣款汇总', async () => {
    command.mockResolvedValue({})
    await payrollService.confirm({
      resultBusinessId: 'PYR-1',
      expectedRowVersion: 2,
      referenceFingerprint: 'f'.repeat(64),
      confirmedAmount: '1000.00',
      note: '人工核定',
      processingItems: [
        { referenceLineBusinessId: 'PYL-1', manualAmount: '10.00', note: '处理一' },
        { referenceLineBusinessId: 'PYL-2', manualAmount: null, note: null },
        { referenceLineBusinessId: 'PYL-3', manualAmount: '0.00', note: '明确为零' },
      ],
    })
    const [, options] = command.mock.calls[0] as [string, Record<string, unknown>]
    const body = options.body as Record<string, unknown>
    expect(body.confirmedAmount).toEqual({ amount: '1000.00', currency: 'CNY' })
    const items = body.processingItems as Array<{ manualAmount: unknown }>
    expect(items[1]?.manualAmount).toBeNull()
    expect(items[2]?.manualAmount).toEqual({ amount: '0.00', currency: 'CNY' })
    expect('deductionTotal' in body).toBe(false)
  })

  it('胶水设置发布携带生效月与免扣重量', async () => {
    command.mockResolvedValue({})
    await payrollService.publishGluePolicy({
      effectiveMonth: '2026-10-01',
      exemptWeightGrams: '300.0000',
      reasonText: '设置',
      expectedGuardVersion: 0,
    })
    const [path] = command.mock.calls[0] as [string]
    expect(path).toBe('/api/payroll/glue/policies')
  })
})
