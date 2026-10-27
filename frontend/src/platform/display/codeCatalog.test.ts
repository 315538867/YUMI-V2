import { describe, expect, it } from 'vitest'
import { catalog, codeLabel, codeTagType, codesOf, categories } from '@/platform/display/codeCatalog'

const allowedTags = new Set(['success', 'warning', 'info', 'danger', 'primary', ''])

describe('展示层编码词典', () => {
  it('每个分类的每个编码都有中文标签和合法标签样式', () => {
    expect(categories().length).toBeGreaterThan(0)
    for (const category of categories()) {
      const codes = codesOf(category)
      expect(codes.length, `分类 ${category} 没有编码`).toBeGreaterThan(0)
      for (const code of codes) {
        const entry = catalog.categories[category]![code]!
        expect(entry.label.trim(), `${category}.${code} 缺少中文标签`).not.toBe('')
        expect(entry.label, `${category}.${code} 的标签不能等于编码`).not.toBe(code)
        expect(allowedTags.has(entry.tag), `${category}.${code} 的 tag 不合法`).toBe(true)
      }
    }
  })

  it('已知编码返回中文，未知编码返回中文占位而不是原始编码', () => {
    expect(codeLabel('order.status', 'CONFIRMED')).toBe('已确认')
    expect(codeLabel('production.targetProcess', 'SEAM_CUTTING')).toBe('缝边剪袋')
    expect(codeLabel('finance.payrollResultStatus', 'REFERENCE_READY')).toBe('参考就绪')
    expect(codeLabel('reporting.feedCode', 'CUSTOMER')).toBe('客户')
    expect(codeLabel('platform.permission', 'order.read')).toBe('订单查看')

    expect(codeLabel('order.status', 'NOT_A_REAL_CODE')).toBe('未知')
    expect(codeLabel('order.status', null)).toBe('—')
    expect(codeTagType('order.status', 'NOT_A_REAL_CODE')).toBe('info')
  })
})
