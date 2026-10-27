import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { Component } from 'vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'
import { useAuthStore } from '@/platform/stores/auth'
import { installElementPlus } from '@/platform/i18n/elementLocale'

function accountWith(permissions: string[]) {
  return {
    accountBusinessId: 'acc-1',
    loginName: 'tester',
    sessionBusinessId: 'session-1',
    permissions,
  }
}

function mountWith(component: Component, props: Record<string, unknown>) {
  return mount(component, {
    props,
    slots: { default: '<span class="inner">操作</span>' },
    global: {
      plugins: [
        {
          install: (app: Parameters<typeof installElementPlus>[0]) => installElementPlus(app),
        },
      ],
    },
  })
}

describe('权限显隐组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('有权限时展示按钮，并把属性与点击事件传给 el-button', async () => {
    useAuthStore().account = accountWith(['order.ship'])
    const click = vi.fn()
    const wrapper = mountWith(PermissionButton, {
      permission: 'order.ship',
      type: 'primary',
      onClick: click,
    })

    expect(wrapper.find('.inner').exists()).toBe(true)
    expect(wrapper.classes()).toContain('el-button--primary')
    await wrapper.trigger('click')
    expect(click).toHaveBeenCalledTimes(1)
  })

  it('无权限时按钮不渲染', () => {
    useAuthStore().account = accountWith([])
    expect(mountWith(PermissionButton, { permission: 'order.ship' }).find('.inner').exists()).toBe(
      false,
    )
  })

  it('未登录时按钮不渲染', () => {
    expect(mountWith(PermissionButton, { permission: 'order.read' }).find('.inner').exists()).toBe(
      false,
    )
  })
})
