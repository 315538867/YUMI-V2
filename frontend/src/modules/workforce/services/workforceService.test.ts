import { describe, expect, it, vi } from 'vitest'
import { workforceService } from '@/modules/workforce/services/workforceService'

const query = vi.fn()
const command = vi.fn()

vi.mock('@/platform/services/api', () => ({
  api: {
    query: (...args: unknown[]) => query(...args),
    command: (...args: unknown[]) => command(...args),
  },
}))

describe('人员 service', () => {
  it('离职与返聘只提交人员命令，不携带账号字段', async () => {
    command.mockResolvedValue({})
    await workforceService.leave('EMP-1', {
      effectiveDate: '2026-10-20',
      reason: '个人原因',
      expectedRowVersion: 2,
    })
    await workforceService.rehire('EMP-1', {
      effectiveDate: '2026-11-01',
      expectedRowVersion: 3,
    })
    const leave = command.mock.calls[0] as [string, Record<string, unknown>]
    const rehire = command.mock.calls[1] as [string, Record<string, unknown>]
    expect(leave[0]).toBe('/api/workforce/employees/EMP-1/leave')
    expect(rehire[0]).toBe('/api/workforce/employees/EMP-1/rehire')
    expect('loginName' in (leave[1].body as Record<string, unknown>)).toBe(false)
    expect(typeof leave[1].idempotencyKey).toBe('string')
  })

  it('资格授予与撤销使用不同路径', async () => {
    command.mockResolvedValue({})
    await workforceService.changeQualification('EMP-1', 'grant', {
      workTypeCode: 'MAKING',
      effectiveDate: '2026-10-20',
    })
    await workforceService.changeQualification('EMP-1', 'revoke', {
      workTypeCode: 'MAKING',
      effectiveDate: '2026-10-21',
    })
    expect((command.mock.calls[0] as [string])[0]).toContain('/qualifications/grant')
    expect((command.mock.calls[1] as [string])[0]).toContain('/qualifications/revoke')
  })
})
