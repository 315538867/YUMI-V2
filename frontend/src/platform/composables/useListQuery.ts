import { nextTick, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const DEFAULT_SIZES = [20, 50, 100]

interface ListQueryOptions {
  defaultSize?: number
  sizes?: number[]
  paged?: boolean
  onSearch: () => void
}

function stableQuery(query: Record<string, unknown>): string {
  return JSON.stringify(
    Object.keys(query)
      .sort()
      .map((key) => [key, query[key]]),
  )
}

/**
 * 列表查询的唯一入口：筛选、页码、每页条数与 URL 双向同步。
 * 页面只负责传默认值并实现 onSearch，刷新/分享/后退都能恢复同一条查询。
 */
export function useListQuery<T extends Record<string, string>>(
  defaults: T,
  options: ListQueryOptions,
) {
  const route = useRoute()
  const router = useRouter()
  const routeName = route.name
  const defaultSize = options.defaultSize ?? 20
  const sizes = options.sizes ?? DEFAULT_SIZES
  const paged = options.paged ?? true
  const filters = reactive({ ...defaults }) as T
  const page = ref(1)
  const size = ref(defaultSize)
  let lastQuery = stableQuery(route.query)

  function queryValue(key: string): string | null {
    const raw = route.query[key]
    return typeof raw === 'string' ? raw : null
  }

  function hydrate(): void {
    for (const key of Object.keys(defaults)) {
      const raw = queryValue(key)
      ;(filters as Record<string, string>)[key] = raw ?? defaults[key] ?? ''
    }
    const nextPage = Number(queryValue('page'))
    page.value = Number.isInteger(nextPage) && nextPage >= 1 ? nextPage : 1
    const nextSize = Number(queryValue('size'))
    size.value = sizes.includes(nextSize) ? nextSize : defaultSize
  }

  async function sync(): Promise<void> {
    const query: Record<string, string> = {}
    for (const [key, value] of Object.entries(route.query)) {
      if (typeof value === 'string') {
        query[key] = value
      }
    }
    for (const key of Object.keys(defaults)) {
      const value = String((filters as Record<string, string>)[key] ?? '')
      if (value === '' || value === defaults[key]) {
        delete query[key]
      } else {
        query[key] = value
      }
    }
    if (paged) {
      if (page.value > 1) {
        query.page = String(page.value)
      } else {
        delete query.page
      }
      if (size.value !== defaultSize) {
        query.size = String(size.value)
      } else {
        delete query.size
      }
    }
    const next = stableQuery(query)
    if (next === lastQuery) {
      return
    }
    lastQuery = next
    await router.replace({ query })
  }

  async function search(): Promise<void> {
    page.value = 1
    await sync()
    options.onSearch()
  }

  async function reset(): Promise<void> {
    Object.assign(filters, defaults)
    page.value = 1
    size.value = defaultSize
    await sync()
    options.onSearch()
  }

  async function goPage(next: number): Promise<void> {
    page.value = Math.max(1, Math.floor(next))
    await sync()
    options.onSearch()
  }

  async function changeSize(next: number): Promise<void> {
    if (!sizes.includes(next)) {
      return
    }
    size.value = next
    page.value = 1
    await sync()
    options.onSearch()
  }

  watch(
    () => route.query,
    async () => {
      if (route.name !== routeName) {
        return
      }
      await nextTick()
      const next = stableQuery(route.query)
      if (next === lastQuery) {
        return
      }
      lastQuery = next
      hydrate()
      options.onSearch()
    },
  )

  hydrate()
  return { filters, page, size, sizes, search, reset, goPage, changeSize, sync }
}
