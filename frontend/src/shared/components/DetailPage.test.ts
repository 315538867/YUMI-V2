import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import DetailPage from '@/shared/components/DetailPage.vue'
import { installElementPlus } from '@/platform/i18n/elementLocale'

function mountDetail(props: Record<string, unknown> = {}) {
  return mount(DetailPage, {
    props: { title: '客户名称', ...props },
    slots: {
      meta: '<span class="meta-text">CUS-001 · 更新于 2026-10-27</span>',
      actions: '<button class="edit">编辑资料</button>',
      default: '<div class="content">详情内容</div>',
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

describe('详情页外壳', () => {
  it('渲染标题、元信息、动作与详情插槽', () => {
    const wrapper = mountDetail()
    expect(wrapper.get('h1').text()).toBe('客户名称')
    expect(wrapper.find('.meta-text').exists()).toBe(true)
    expect(wrapper.find('.edit').exists()).toBe(true)
    expect(wrapper.find('.content').exists()).toBe(true)
  })

  it('加载失败时展示错误并支持重试', async () => {
    const wrapper = mountDetail({ error: '详情加载失败' })
    expect(wrapper.text()).toContain('详情加载失败')
    await wrapper.get('[data-test="retry"]').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })
})
