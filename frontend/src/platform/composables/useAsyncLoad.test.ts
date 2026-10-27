import { describe, expect, it } from 'vitest'
import { ApiClientError, ApiErrorTypes } from '@/platform/services/apiClient'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'

describe('统一读操作状态', () => {
  it('成功后返回结果并结束加载', async () => {
    const load = useAsyncLoad()
    const result = await load.run(() => Promise.resolve('ok'), '加载失败')

    expect(result).toBe('ok')
    expect(load.loading.value).toBe(false)
    expect(load.errorText.value).toBe('')
  })

  it('失败时写入后端中文消息并结束加载', async () => {
    const load = useAsyncLoad()
    const result = await load.run(
      () =>
        Promise.reject(
          new ApiClientError({
            type: ApiErrorTypes.BUSINESS_REJECTION,
            code: 'ORDER_NOT_READY',
            message: '订单尚未满足条件',
          }),
        ),
      '加载失败',
    )

    expect(result).toBeUndefined()
    expect(load.loading.value).toBe(false)
    expect(load.errorText.value).toBe('订单尚未满足条件')
  })

  it('非接口错误使用兜底文案，重置后清空错误', async () => {
    const load = useAsyncLoad()
    await load.run(() => Promise.reject(new Error('network')), '客户资料加载失败')

    expect(load.errorText.value).toBe('客户资料加载失败')
    load.reset()
    expect(load.errorText.value).toBe('')
  })

  it('重新加载前清空上一次错误', async () => {
    const load = useAsyncLoad()
    await load.run(() => Promise.reject(new Error('network')), '列表加载失败')
    const second = await load.run(() => Promise.resolve(2), '列表加载失败')

    expect(second).toBe(2)
    expect(load.errorText.value).toBe('')
  })

  it('加载中保持 loading，结束后复位', async () => {
    const load = useAsyncLoad()
    let release: (value: string) => void = () => {}
    const pending = load.run(
      () =>
        new Promise<string>((resolve) => {
          release = resolve
        }),
      '加载失败',
    )

    expect(load.loading.value).toBe(true)
    release('done')
    await pending
    expect(load.loading.value).toBe(false)
  })

  it('不吞掉任务返回的 false 或 0', async () => {
    const load = useAsyncLoad()
    expect(await load.run(() => Promise.resolve(false), '加载失败')).toBe(false)
    expect(await load.run(() => Promise.resolve(0), '加载失败')).toBe(0)
  })

  it('编辑页可指定初始加载态，避免先渲染空表单', () => {
    const load = useAsyncLoad(true)
    expect(load.loading.value).toBe(true)
  })

  it('嵌套加载保留内层错误，不被外层成功覆盖', async () => {
    const load = useAsyncLoad()
    await load.run(async () => {
      await load.run(() => Promise.reject(new Error('inner')), '内层加载失败')
    }, '外层加载失败')

    expect(load.errorText.value).toBe('内层加载失败')
    expect(load.loading.value).toBe(false)
  })

  it('页面可自行处理特殊错误码，不写入通用错误', async () => {
    const load = useAsyncLoad()
    const result = await load.run(
      () =>
        Promise.reject(
          new ApiClientError({
            type: ApiErrorTypes.NOT_FOUND,
            code: 'PAYROLL_RESULT_NOT_FOUND',
            message: '员工工资结果不存在',
          }),
        ),
      '工资结果加载失败',
      { handled: (error) => (error as ApiClientError).code === 'PAYROLL_RESULT_NOT_FOUND' },
    )

    expect(result).toBeUndefined()
    expect(load.errorText.value).toBe('')
  })
})
