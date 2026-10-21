import { describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import App from '@/app/App.vue'
import router from '@/router'

describe('应用外壳', () => {
  it('挂载后渲染路由出口', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    await router.push('/login')
    await router.isReady()

    const wrapper = mount(App, {
      global: { plugins: [pinia, router] },
    })

    expect(wrapper.find('.yumi-app').exists()).toBe(true)
  })
})
