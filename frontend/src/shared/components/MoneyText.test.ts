import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import MoneyText from '@/shared/components/MoneyText.vue'

describe('金额展示', () => {
  it('支持金额对象、十进制字符串与数值', () => {
    expect(
      mount(MoneyText, { props: { value: { amount: '12.30', currency: 'CNY' } } }).text(),
    ).toBe('12.30 CNY')
    expect(mount(MoneyText, { props: { value: '12.30' } }).text()).toBe('12.30')
    expect(mount(MoneyText, { props: { value: 12.3 } }).text()).toBe('12.30')
  })

  it('空值显示占位符', () => {
    expect(mount(MoneyText, { props: { value: null } }).text()).toBe('—')
  })
})
