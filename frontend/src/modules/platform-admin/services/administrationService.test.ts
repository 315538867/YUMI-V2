import { describe, expect, it, vi } from 'vitest'
import { administrationService } from '@/modules/platform-admin/services/administrationService'

const query = vi.fn()
const command = vi.fn()

vi.mock('@/platform/services/api', () => ({
  api: {
    query: (...args: unknown[]) => query(...args),
    command: (...args: unknown[]) => command(...args),
  },
}))

describe('平台管理 service', () => {
  it('任务恢复只调用 platform 管理命令', async () => {
    command.mockResolvedValue({})
    await administrationService.retryTask('TASK-1')
    const [path, options] = command.mock.calls[0] as [string, Record<string, unknown>]
    expect(path).toBe('/api/platform/tasks/TASK-1/retry')
    expect(typeof options.idempotencyKey).toBe('string')
  })

  it('审计检索只读', async () => {
    query.mockResolvedValue([])
    await administrationService.auditByTarget('WORKFORCE', 'EMP-1')
    expect(query).toHaveBeenCalledWith(
      '/api/platform/audit?targetType=WORKFORCE&targetBusinessId=EMP-1&limit=50',
    )
  })
})
