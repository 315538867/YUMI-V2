import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'

describe('工作区页面外壳', () => {
  it('默认内边距 16px 并渲染内容', () => {
    const wrapper = mount(WorkspacePage, { slots: { default: '<p class="content">内容</p>' } })
    expect(wrapper.classes()).toContain('workspace-page')
    expect(wrapper.attributes('style')).toContain('padding: 16px')
    expect(wrapper.find('.content').exists()).toBe(true)
  })

  it('父级 class 合并到根节点，允许按内容调整内边距', () => {
    const wrapper = mount(WorkspacePage, {
      props: { padding: 20 },
      attrs: { class: 'order-list' },
    })
    expect(wrapper.classes()).toContain('order-list')
    expect(wrapper.attributes('style')).toContain('padding: 20px')
  })
})
