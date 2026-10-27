import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiClientError } from '@/platform/services/apiClient'
import { userMessageOf } from '@/platform/services/errorMessage'

interface RunOptions<T> {
  success?: string
  fallback?: string
  onSuccess?: (value: T) => void
  onError?: (error: unknown) => void
}

/**
 * 写操作统一错误策略：接口错误 toast，字段错误留在表单内；页面不重复写 catch。
 */
export function useAsyncAction() {
  const loading = ref(false)
  const fieldErrors = ref<Record<string, string>>({})

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
      } else {
        ElMessage.error(userMessageOf(error, options.fallback ?? '操作失败，请稍后重试'))
      }
      options.onError?.(error)
      return undefined
    } finally {
      loading.value = false
    }
  }

  return { loading, fieldErrors, run }
}
