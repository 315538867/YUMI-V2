import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import ModuleTabsPage from '@/shared/components/ModuleTabsPage.vue'
import { installElementPlus } from '@/platform/i18n/elementLocale'

const { push } = vi.hoisted(() => ({ push: vi.fn() }))

vi.mock('vue-router', () => ({
  useRoute: () => ({ path: '/reporting/explorer' }),
  useRouter: () => ({ push }),
  RouterView: defineComponent({ name: 'RouterView', template: '<div class="route-view" />' }),
}))

function mountModule() {
  return mount(ModuleTabsPage, {
    props: {
      title: '报表',
      hint: '月度、主题、来源对账与影子重建',
      tabs: [
        { path: '/reporting/monthly', label: '月汇总' },
        { path: '/reporting/explorer', label: '主题报表' },
      ],
    },
    slots: {
      overview: '<div class="overview">概览</div>',
      actions: '<button class="create">新增员工</button>',
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

describe('模块页签外壳', () => {
  it('模块标题在页签之前渲染', () => {
    const wrapper = mountModule()
    const html = wrapper.html()
    expect(wrapper.get('h1').text()).toBe('报表')
    expect(wrapper.text()).toContain('月度、主题、来源对账与影子重建')
    expect(html.indexOf('yumi-page-heading')).toBeLessThan(html.indexOf('yumi-module-tabs'))
    expect(wrapper.find('.overview').exists()).toBe(true)
    expect(wrapper.find('.create').exists()).toBe(true)
    expect(wrapper.find('.route-view').exists()).toBe(true)
  })

  it('切换页签时跳转到对应路径', async () => {
    const wrapper = mountModule()
    const tabs = wrapper.findAll('.el-tabs__item')
    expect(tabs.length).toBe(2)
    const [firstTab] = tabs
    if (!firstTab) throw new Error('缺少页签')
    await firstTab.trigger('click')
    expect(push).toHaveBeenCalledWith('/reporting/monthly')
  })
})
