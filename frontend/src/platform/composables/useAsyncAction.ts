import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiClientError } from '@/platform/services/apiClient'
import { userMessageOf } from '@/platform/services/errorMessage'

interface RunOptions<T> {
  success?: string
  fallback?: string
  onSuccess?: (value: T) => void
  onError?: (error: unknown) => void
  /** 返回 true 表示页面已内联处理，不再弹 toast。 */
  handled?: (error: unknown) => boolean
}

/**
 * 写操作统一错误策略：接口错误 toast，字段错误留在表单内；页面不重复写 catch。
 */
export function useAsyncAction() {
  const loading = ref(false)
  const fieldErrors = ref<Record<string, string>>({})
  const fieldErrorSummary = computed(() => Object.values(fieldErrors.value).join('；'))

  /** 兼容后端路径型字段名（如 definition.name、items[0].quantity）。 */
  function fieldError(...keys: string[]): string {
    for (const key of keys) {
      if (fieldErrors.value[key]) {
        return fieldErrors.value[key]
      }
      const match = Object.entries(fieldErrors.value).find(
        ([field]) => field.endsWith(`.${key}`) || field.includes(`[${key}]`),
      )
      if (match) {
        return match[1]
      }
    }
    return ''
  }

  async function run<T>(
    action: () => Promise<T>,
    options: RunOptions<T> = {},
  ): Promise<T | undefined> {
    loading.value = true
    fieldErrors.value = {}
    try {
      const value = await action()
      options.onSuccess?.(value)
      if (options.success) {
        ElMessage.success(options.success)
      }
      return value
    } catch (error) {
      if (error instanceof ApiClientError && error.fieldIssues.length > 0) {
        fieldErrors.value = Object.fromEntries(
          error.fieldIssues.map((issue) => [issue.field, issue.reason]),
        )
      } else if (!options.handled?.(error)) {
        ElMessage.error(userMessageOf(error, options.fallback ?? '操作失败，请稍后重试'))
      }
      options.onError?.(error)
      return undefined
    } finally {
      loading.value = false
    }
  }

  /** 未被指定字段消费的错误，用表单内汇总展示，避免静默。 */
  function otherFieldErrors(...keys: string[]): string[] {
    return Object.entries(fieldErrors.value)
      .filter(
        ([field]) =>
          !keys.some(
            (key) => field === key || field.endsWith(`.${key}`) || field.includes(`[${key}]`),
          ),
      )
      .map(([, reason]) => reason)
  }

  return { loading, fieldErrors, fieldErrorSummary, fieldError, otherFieldErrors, run }
}
