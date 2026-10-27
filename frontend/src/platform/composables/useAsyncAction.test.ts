import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClientError, ApiErrorTypes } from '@/platform/services/apiClient'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'

const success = vi.fn()
const error = vi.fn()

vi.mock('element-plus', () => ({
  ElMessage: {
    success: (...args: unknown[]) => success(...args),
    error: (...args: unknown[]) => error(...args),
  },
}))

describe('统一写操作错误策略', () => {
  beforeEach(() => {
    success.mockReset()
    error.mockReset()
  })

  it('接口错误以 toast 提示，不写入字段错误', async () => {
    const action = useAsyncAction()
    const result = await action.run(
      () =>
        Promise.reject(
          new ApiClientError({
            type: ApiErrorTypes.BUSINESS_REJECTION,
            code: 'ORDER_NOT_READY',
            message: '订单尚未满足条件',
          }),
        ),
      { fallback: '操作失败' },
    )

    expect(result).toBeUndefined()
    expect(error).toHaveBeenCalledWith('订单尚未满足条件')
    expect(action.fieldErrors.value).toEqual({})
  })

  it('字段错误留在表单内，不弹 toast', async () => {
    const action = useAsyncAction()
    await action.run(() =>
      Promise.reject(
        new ApiClientError({
          type: ApiErrorTypes.VALIDATION,
          code: 'INVALID_FIELD',
          message: '请求参数不合法',
          fieldIssues: [{ field: 'amount', reason: '金额必须大于 0' }],
        }),
      ),
    )

    expect(action.fieldErrors.value).toEqual({ amount: '金额必须大于 0' })
    expect(error).not.toHaveBeenCalled()
  })

  it('成功时按需提示并执行回调', async () => {
    const action = useAsyncAction()
    const onSuccess = vi.fn()
    const result = await action.run(() => Promise.resolve('ok'), {
      success: '保存成功',
      onSuccess,
    })

    expect(result).toBe('ok')
    expect(success).toHaveBeenCalledWith('保存成功')
    expect(onSuccess).toHaveBeenCalledWith('ok')
  })
})
