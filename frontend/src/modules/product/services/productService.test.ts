import { describe, expect, it, vi } from 'vitest'
import { productService } from '@/modules/product/services/productService'

const query = vi.fn()
const command = vi.fn()

vi.mock('@/platform/services/api', () => ({
  api: {
    query: (...args: unknown[]) => query(...args),
    command: (...args: unknown[]) => command(...args),
  },
}))

describe('商品 service', () => {
  it('商品列表按状态与关键字查询', async () => {
    query.mockResolvedValue([])
    await productService.list('标准', 'ACTIVE')
    expect(query).toHaveBeenCalledWith(
      '/api/products?keyword=%E6%A0%87%E5%87%86&status=ACTIVE&page=1&size=20',
    )
  })

  it('新建方案携带商品内容版本，人工字段不从页面提交', async () => {
    command.mockResolvedValue({})
    await productService.createScheme(
      'PRD-1',
      {
        name: '客户供料',
        targetProcess: 'SEAM_CUTTING',
        salePrice: { amount: '60.00', currency: 'CNY' },
        glueSource: 'CUSTOMER',
        colorPasteSource: 'CUSTOMER',
        makingOtherMaterialCost: { amount: '1.00', currency: 'CNY' },
        baggingBagUnitCost: { amount: '1.00', currency: 'CNY' },
        baggingBagSource: 'CUSTOMER',
        baggingDecorationUnitPrice: { amount: '1.00', currency: 'CNY' },
        baggingDecorationSource: 'CUSTOMER',
        baggingOtherCost: { amount: '1.00', currency: 'CNY' },
        seamCombinedMaterialCost: { amount: '1.00', currency: 'CNY' },
        seamOtherCost: { amount: '1.00', currency: 'CNY' },
      },
      7,
    )
    const [path, options] = command.mock.calls[0] as [string, Record<string, unknown>]
    const body = options.body as Record<string, unknown>
    expect(path).toBe('/api/products/PRD-1/schemes')
    expect(body.expectedProductContentVersion).toBe(7)
    expect('makingCommission' in body).toBe(false)
    expect('seamLaborCost' in body).toBe(false)
  })
})
