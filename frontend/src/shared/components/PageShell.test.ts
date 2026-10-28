import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import PageShell from '@/shared/components/PageShell.vue'

describe('裸页面页头外壳', () => {
  it('渲染标题、说明、动作与内容', () => {
    const wrapper = mount(PageShell, {
      props: { title: '生产作业', hint: '整体进度、周排班与核验' },
      slots: {
        actions: '<button class="action">安排任务</button>',
        default: '<section class="body">内容</section>',
      },
    })
    expect(wrapper.get('h1').text()).toBe('生产作业')
    expect(wrapper.text()).toContain('整体进度、周排班与核验')
    expect(wrapper.find('.action').exists()).toBe(true)
    expect(wrapper.find('.body').exists()).toBe(true)
    expect(wrapper.find('header').classes()).toContain('yumi-page-heading')
  })

  it('无说明时不渲染说明节点', () => {
    const wrapper = mount(PageShell, { props: { title: '来源对账' } })
    expect(wrapper.find('.yumi-page-heading__hint').exists()).toBe(false)
  })
})
