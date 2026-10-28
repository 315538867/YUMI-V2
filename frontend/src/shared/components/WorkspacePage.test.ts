import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'

describe('工作区页面外壳', () => {
  it('默认使用 md 内边距并渲染内容', () => {
    const wrapper = mount(WorkspacePage, { slots: { default: '<p class="content">内容</p>' } })
    expect(wrapper.classes()).toContain('workspace-page')
    expect(wrapper.classes()).toContain('workspace-page--md')
    expect(wrapper.find('.content').exists()).toBe(true)
  })

  it('父级 class 合并到根节点，允许按内容选择内边距档位', () => {
    const wrapper = mount(WorkspacePage, {
      props: { padding: 'lg' },
      attrs: { class: 'order-list' },
    })
    expect(wrapper.classes()).toContain('order-list')
    expect(wrapper.classes()).toContain('workspace-page--lg')
  })
})
