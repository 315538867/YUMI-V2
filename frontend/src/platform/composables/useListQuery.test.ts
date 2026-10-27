import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { useListQuery } from '@/platform/composables/useListQuery'

const mocks = vi.hoisted(() => ({
  route: {} as {
    name: string
    query: Record<string, string>
    fullPath: string
  },
  routeProxy: {} as {
    name: string
    query: Record<string, string>
    fullPath: string
  },
  replace: vi.fn(),
}))

vi.mock('vue-router', async () => {
  const { reactive } = await import('vue')
  mocks.routeProxy = reactive(mocks.route) as typeof mocks.route
  return {
    useRoute: () => mocks.routeProxy,
    useRouter: () => ({ replace: mocks.replace }),
  }
})

describe('列表查询 URL 同步', () => {
  beforeEach(() => {
    mocks.replace.mockReset()
    mocks.replace.mockImplementation(async ({ query }: { query: Record<string, string> }) => {
      mocks.routeProxy.query = query
    })
    mocks.routeProxy.name = 'customers'
    mocks.routeProxy.query = {}
    mocks.routeProxy.fullPath = '/customers'
  })

  it('从 URL 恢复筛选、页码和每页条数', () => {
    mocks.routeProxy.query = {
      keyword: '张三',
      status: 'ACTIVE',
      page: '2',
      size: '50',
    }
    const onSearch = vi.fn()
    const query = useListQuery({ keyword: '', status: '' }, { onSearch })

    expect(query.filters).toEqual({ keyword: '张三', status: 'ACTIVE' })
    expect(query.page.value).toBe(2)
    expect(query.size.value).toBe(50)
    expect(onSearch).not.toHaveBeenCalled()
  })

  it('查询时回到第一页并只把非默认条件写入 URL', async () => {
    const onSearch = vi.fn()
    const query = useListQuery({ keyword: '', status: '' }, { onSearch })
    query.filters.keyword = '李四'
    query.page.value = 3

    await query.search()

    expect(onSearch).toHaveBeenCalledTimes(1)
    expect(mocks.replace).toHaveBeenCalledWith({ query: { keyword: '李四' } })
  })

  it('重置清空条件、页码与自定义每页条数', async () => {
    mocks.routeProxy.query = { keyword: '李四', page: '3', size: '50' }
    const onSearch = vi.fn()
    const query = useListQuery({ keyword: '', status: '' }, { onSearch })

    await query.reset()

    expect(query.filters.keyword).toBe('')
    expect(query.page.value).toBe(1)
    expect(query.size.value).toBe(20)
    expect(mocks.replace).toHaveBeenCalledWith({ query: {} })
  })

  it('翻页和切换每页条数都会重新查询', async () => {
    const onSearch = vi.fn()
    const query = useListQuery({ keyword: '' }, { onSearch })

    await query.goPage(2)
    expect(query.page.value).toBe(2)
    expect(onSearch).toHaveBeenCalledTimes(1)

    await query.changeSize(50)
    expect(query.size.value).toBe(50)
    expect(query.page.value).toBe(1)
    expect(onSearch).toHaveBeenCalledTimes(2)

    await query.changeSize(999)
    expect(query.size.value).toBe(50)
    expect(onSearch).toHaveBeenCalledTimes(2)
  })

  it('浏览器后退改变 URL 时重新同步条件并查询', async () => {
    const onSearch = vi.fn()
    const query = useListQuery({ keyword: '' }, { onSearch })

    mocks.routeProxy.query = { keyword: '王五', page: '2' }
    await nextTick()
    await nextTick()

    expect(query.filters.keyword).toBe('王五')
    expect(query.page.value).toBe(2)
    expect(onSearch).toHaveBeenCalledTimes(1)
  })
})
