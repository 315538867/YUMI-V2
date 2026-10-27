import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ListPager from '@/shared/components/ListPager.vue'
import PageState from '@/shared/components/PageState.vue'
import { installElementPlus } from '@/platform/i18n/elementLocale'

function mountWith(component: Parameters<typeof mount>[0], props: Record<string, unknown>) {
  return mount(component, {
    props,
    slots: { default: '<span class="content">内容</span>' },
    global: {
      plugins: [
        {
          install: (app: Parameters<typeof installElementPlus>[0]) => installElementPlus(app),
        },
      ],
    },
  })
}

describe('列表分页', () => {
  it('满页时允许下一页，不足一页时禁用', async () => {
    const full = mountWith(ListPager, { page: 1, size: 20, count: 20 })
    expect(full.get('[data-test="next"]').attributes('disabled')).toBeUndefined()
    expect(full.get('[data-test="prev"]').attributes('disabled')).toBeDefined()

    const partial = mountWith(ListPager, { page: 2, size: 20, count: 8 })
    expect(partial.get('[data-test="next"]').attributes('disabled')).toBeDefined()
    expect(partial.get('[data-test="prev"]').attributes('disabled')).toBeUndefined()
  })

  it('点击上一页发出页码更新', async () => {
    const wrapper = mountWith(ListPager, { page: 2, size: 20, count: 20 })
    await wrapper.get('[data-test="prev"]').trigger('click')
    expect(wrapper.emitted('update:page')?.[0]).toEqual([1])
  })
})

describe('页面状态', () => {
  it('失败时展示错误并可重试', async () => {
    const wrapper = mountWith(PageState, { error: '列表加载失败' })
    expect(wrapper.text()).toContain('列表加载失败')
    await wrapper.get('[data-test="retry"]').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('空闲且无数据时展示空态文案', () => {
    const wrapper = mountWith(PageState, { empty: true, emptyText: '没有匹配的客户' })
    expect(wrapper.text()).toContain('没有匹配的客户')
    expect(wrapper.find('.content').exists()).toBe(false)
  })

  it('有数据时渲染内容', () => {
    const wrapper = mountWith(PageState, { empty: false })
    expect(wrapper.find('.content').exists()).toBe(true)
  })
})
