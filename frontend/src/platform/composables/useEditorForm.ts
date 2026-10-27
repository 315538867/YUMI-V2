import { computed, reactive, ref } from 'vue'
import { isErrorType } from '@/platform/services/errorMessage'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'

export interface ValidationRule {
  field: string
  invalid: boolean
  message: string
}

interface SubmitOptions<T> {
  success: string
  fallback: string
  conflictMessage?: string
  onSuccess?: (value: T) => void
}

/**
 * 编辑页统一表单状态：必填校验、接口字段错误、表单内汇总和版本冲突内联。
 * 页面只声明字段规则与提交动作，不再重复拼错误状态。
 */
export function useEditorForm(inlineFields: string[], defaultConflictMessage: string) {
  const validationErrors = reactive<Record<string, string>>({})
  const conflictError = ref('')
  const action = useAsyncAction()

  const formAlert = computed(
    () =>
      conflictError.value ||
      validationErrors._form ||
      action.otherFieldErrors(...inlineFields).join('；'),
  )

  function validate(rules: ValidationRule[]): boolean {
    conflictError.value = ''
    for (const key of Object.keys(validationErrors)) {
      delete validationErrors[key]
    }
    for (const rule of rules) {
      if (rule.invalid) {
        validationErrors[rule.field] = rule.message
      }
    }
    return Object.keys(validationErrors).length === 0
  }

  async function submit<T>(
    request: () => Promise<T>,
    options: SubmitOptions<T>,
  ): Promise<T | undefined> {
    return action.run(request, {
      success: options.success,
      fallback: options.fallback,
      ...(options.onSuccess === undefined ? {} : { onSuccess: options.onSuccess }),
      handled: (error) => {
        if (isErrorType(error, 'CONCURRENT_CONFLICT')) {
          conflictError.value = options.conflictMessage ?? defaultConflictMessage
          return true
        }
        return false
      },
    })
  }

  return {
    saving: action.loading,
    validationErrors,
    formAlert,
    fieldError: action.fieldError,
    validate,
    submit,
  }
}
