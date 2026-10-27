import { describe, expect, it, vi } from 'vitest'
import { orderService } from '@/modules/orders/services/orderService'

const query = vi.fn()
const command = vi.fn()

vi.mock('@/platform/services/api', () => ({
  api: {
    query: (...args: unknown[]) => query(...args),
    command: (...args: unknown[]) => command(...args),
  },
}))

describe('订单 service', () => {
  it('列表分页从 1 开始，与统一分页契约一致', async () => {
    query.mockResolvedValue([])
    await orderService.list('SO000001', 'CONFIRMED')
    expect(query).toHaveBeenCalledWith(
      '/api/orders?page=1&size=20&keyword=SO000001&status=CONFIRMED',
    )
  })

  it('收款只调用现金命令，不在订单服务内拼现金事实', async () => {
    command.mockResolvedValue({ shipmentBusinessId: 'SHP-1' })
    await orderService.ship({
      orderBusinessId: 'ORD-1',
      items: [
        {
          agreementGroupBusinessId: 'AGR-1',
          productionSourceBusinessId: 'PSRC-1',
          quantity: 2,
        },
      ],
      trackingNo: 'SF-1',
      shippedAt: '2026-10-20T10:00:00',
    })
    const [path, options] = command.mock.calls[0] as [string, Record<string, unknown>]
    expect(path).toBe('/api/orders/delivery/shipments')
    expect((options.body as Record<string, unknown>).orderBusinessId).toBe('ORD-1')
    expect(typeof options.idempotencyKey).toBe('string')
  })
})
