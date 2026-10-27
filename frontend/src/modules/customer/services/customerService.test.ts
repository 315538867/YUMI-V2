import { describe, expect, it, vi } from 'vitest'
import { customerService } from '@/modules/customer/services/customerService'

const query = vi.fn()
const command = vi.fn()

vi.mock('@/platform/services/api', () => ({
  api: {
    query: (...args: unknown[]) => query(...args),
    command: (...args: unknown[]) => command(...args),
  },
}))

describe('客户 service', () => {
  it('列表查询只拼接非空筛选条件', async () => {
    query.mockResolvedValue([])
    await customerService.list('张', '')
    expect(query).toHaveBeenCalledWith('/api/customers?keyword=%E5%BC%A0&page=1&size=20')
  })

  it('修改携带期望行版本与幂等键', async () => {
    command.mockResolvedValue({})
    await customerService.update('CUS-1', {
      name: '张三',
      contactName: '张三',
      phone: '13800000000',
      defaultAddress: '地址',
      expectedRowVersion: 3,
    })
    const [path, options] = command.mock.calls[0] as [string, Record<string, unknown>]
    expect(path).toBe('/api/customers/CUS-1')
    expect(options.method).toBe('PUT')
    expect((options.body as Record<string, unknown>).expectedRowVersion).toBe(3)
    expect(typeof options.idempotencyKey).toBe('string')
  })
})
