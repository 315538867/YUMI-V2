import { ref } from 'vue'
import { userMessageOf } from '@/platform/services/errorMessage'

/**
 * 读操作统一状态：加载、失败文案与重试；错误留在页面由 PageState 展示，不弹 toast。
 */
export function useAsyncLoad(initialLoading = false) {
  const loading = ref(initialLoading)
  const errorText = ref('')
  let active = 0

  async function run<T>(
    task: () => Promise<T>,
    fallback: string,
    options: { handled?: (error: unknown) => boolean } = {},
  ): Promise<T | undefined> {
    const outermost = active === 0
    active += 1
    loading.value = true
    if (outermost) {
      errorText.value = ''
    }
    try {
      return await task()
    } catch (error) {
      if (!options.handled?.(error)) {
        errorText.value = userMessageOf(error, fallback)
      }
      return undefined
    } finally {
      active -= 1
      if (active === 0) {
        loading.value = false
      }
    }
  }

  function reset(): void {
    errorText.value = ''
  }

  return { loading, errorText, run, reset }
}
