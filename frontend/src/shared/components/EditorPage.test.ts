import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import EditorPage from '@/shared/components/EditorPage.vue'
import { installElementPlus } from '@/platform/i18n/elementLocale'

function mountEditor(props: Record<string, unknown> = {}) {
  return mount(EditorPage, {
    props: { title: '编辑客户', ...props },
    slots: {
      actions: '<button class="save">保存</button>',
      default: '<form class="form">表单</form>',
      preview: '<dl class="preview-list"><dt>名称</dt><dd>示例</dd></dl>',
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

describe('编辑页外壳', () => {
  it('渲染标题、操作、表单和预览插槽', () => {
    const wrapper = mountEditor({ previewTitle: '客户资料预览', previewHint: '保存后以服务端为准' })
    expect(wrapper.get('h1').text()).toBe('编辑客户')
    expect(wrapper.find('.save').exists()).toBe(true)
    expect(wrapper.find('.form').exists()).toBe(true)
    expect(wrapper.text()).toContain('客户资料预览')
    expect(wrapper.find('.preview-list').exists()).toBe(true)
    expect(wrapper.text()).toContain('保存后以服务端为准')
  })

  it('加载失败时展示错误并支持重试', async () => {
    const wrapper = mountEditor({ error: '资料加载失败' })
    expect(wrapper.text()).toContain('资料加载失败')
    await wrapper.get('[data-test="retry"]').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })
})
