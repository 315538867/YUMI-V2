import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ListPage from '@/shared/components/ListPage.vue'
import { installElementPlus } from '@/platform/i18n/elementLocale'

function mountList(props: Record<string, unknown> = {}, slots: Record<string, string> = {}) {
  return mount(ListPage, {
    props: { title: '客户', ...props },
    slots: {
      filters: '<input class="keyword" />',
      actions: '<button class="create">新增客户</button>',
      default: '<table class="rows"><tr><td>客户 A</td></tr></table>',
      pager: '<div class="pager">分页</div>',
      ...slots,
    },
    global: {
      plugins: [
        {
          install: (app: Parameters<typeof installElementPlus>[0]) => installElementPlus(app),
        },
      ],
    },
  })
}

describe('列表页外壳', () => {
  it('渲染标题、数量、筛选、动作、表格和分页插槽', () => {
    const wrapper = mountList({ meta: '启用客户 3' })
    expect(wrapper.get('h1').text()).toBe('客户')
    expect(wrapper.text()).toContain('启用客户 3')
    expect(wrapper.find('.keyword').exists()).toBe(true)
    expect(wrapper.find('.create').exists()).toBe(true)
    expect(wrapper.find('.rows').exists()).toBe(true)
    expect(wrapper.find('.pager').exists()).toBe(true)
  })

  it('无筛选和动作时不渲染工具条', () => {
    const wrapper = mount(ListPage, {
      props: { title: '客户' },
      slots: { default: '<p class="content">内容</p>' },
      global: {
        plugins: [
          {
            install: (app: Parameters<typeof installElementPlus>[0]) => installElementPlus(app),
          },
        ],
      },
    })
    expect(wrapper.find('.list-page__toolbar').exists()).toBe(false)
  })

  it('模块子页隐藏页头时只保留卡片与工具条', () => {
    const wrapper = mountList({ heading: false, panelTitle: '员工列表' })
    expect(wrapper.find('h1').exists()).toBe(false)
    expect(wrapper.text()).toContain('员工列表')
    expect(wrapper.find('.keyword').exists()).toBe(true)
  })

  it('失败时展示错误并支持重试', async () => {
    const wrapper = mountList({ error: '列表加载失败' })
    expect(wrapper.text()).toContain('列表加载失败')
    await wrapper.get('[data-test="retry"]').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })
})
