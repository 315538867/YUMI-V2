import { describe, expect, it, vi } from 'vitest'
import {
  ApiErrorTypes,
  createApiClient,
  isMoneyWire,
  parseDecimal,
  toDecimalString,
  type ApiErrorType,
} from '@/platform/services/apiClient'

/**
 * 前端 HTTP 客户端契约。
 *
 * 规则来源：`docs/一期/05-跨模块接口与事务规则.md` §9、§11，`04-公共业务与技术规则.md` §2、§8。
 * 关键点：
 * - 正式命令必须带幂等键，查询必须带请求编号；
 * - 金额以 `{amount, currency}` 十进制字符串传输，前端不得转成 JS number 再计算；
 * - 后端稳定错误类型必须原样保留，页面按类型分支；
 * - 网络失败不能伪装成业务成功或业务拒绝。
 */

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function errorResponse(
  type: ApiErrorType,
  code: string,
  message: string,
  status: number,
  extra: Record<string, unknown> = {},
): Response {
  return jsonResponse(
    { type, code, message, requestId: 'req-server', fieldIssues: [], ...extra },
    status,
  )
}

describe('十进制字符串', () => {
  it('保留原始十进制文本，不经过浮点数', () => {
    expect(toDecimalString('12.5000')).toBe('12.5000')
    expect(parseDecimal('12.5000').toFixed(4)).toBe('12.5000')
  })

  it('拒绝科学计数法与空白', () => {
    expect(() => toDecimalString('1e3')).toThrow()
    expect(() => toDecimalString(' 12.5 ')).toThrow()
    expect(() => toDecimalString('')).toThrow()
  })

  it('金额对象必须同时包含 amount 与 currency', () => {
    expect(isMoneyWire({ amount: '1.0000', currency: 'CNY' })).toBe(true)
    expect(isMoneyWire({ amount: 1, currency: 'CNY' })).toBe(false)
    expect(isMoneyWire({ amount: '1.0000' })).toBe(false)
    expect(isMoneyWire(null)).toBe(false)
  })
})

describe('API 客户端', () => {
  it('查询携带请求编号和 JSON 接受头，不带幂等键', async () => {
    const fetchImpl = vi.fn(async () => jsonResponse({ ok: true }))
    const client = createApiClient({ fetchImpl, requestIdFactory: () => 'req-client' })

    await client.query('/api/customers')

    expect(fetchImpl).toHaveBeenCalledTimes(1)
    const [url, init] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/api/customers')
    expect((init.headers as Record<string, string>)['X-Request-Id']).toBe('req-client')
    expect((init.headers as Record<string, string>)['Idempotency-Key']).toBeUndefined()
    expect(init.credentials).toBe('same-origin')
  })

  it('正式命令必须显式携带幂等键，缺失时本地拒绝且不发请求', async () => {
    const fetchImpl = vi.fn(async () => jsonResponse({ ok: true }))
    const client = createApiClient({ fetchImpl, requestIdFactory: () => 'req-client' })

    await expect(
      client.command('/api/customers', { body: { name: '张三' }, idempotencyKey: '' }),
    ).rejects.toThrow(/幂等/)
    expect(fetchImpl).not.toHaveBeenCalled()
  })

  it('命令发送 JSON 请求体与幂等键', async () => {
    const fetchImpl = vi.fn(async () => jsonResponse({ businessId: 'C-1' }, 201))
    const client = createApiClient({ fetchImpl, requestIdFactory: () => 'req-client' })

    const result = await client.command<{ businessId: string }>('/api/customers', {
      body: { name: '张三' },
      idempotencyKey: 'idem-1',
      method: 'POST',
    })

    expect(result.businessId).toBe('C-1')
    const [, init] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit]
    expect(init.method).toBe('POST')
    expect((init.headers as Record<string, string>)['Idempotency-Key']).toBe('idem-1')
    expect(init.body).toBe(JSON.stringify({ name: '张三' }))
  })

  it('按稳定错误类型映射后端错误，保留错误码、请求编号和字段问题', async () => {
    const fetchImpl = vi.fn(async () =>
      errorResponse('VALIDATION', 'VALIDATION_FAILED', '请求参数不合法', 400, {
        fieldIssues: [{ field: 'quantity', reason: '不能为负' }],
      }),
    )
    const client = createApiClient({ fetchImpl, requestIdFactory: () => 'req-client' })

    await expect(client.query('/api/x')).rejects.toMatchObject({
      type: 'VALIDATION',
      code: 'VALIDATION_FAILED',
      message: '请求参数不合法',
      requestId: 'req-server',
      httpStatus: 400,
      fieldIssues: [{ field: 'quantity', reason: '不能为负' }],
    })
  })

  it('并发冲突与幂等冲突保持不同错误类型，便于页面分别处理', async () => {
    const conflict = createApiClient({
      fetchImpl: async () =>
        errorResponse('CONCURRENT_CONFLICT', 'ROW_VERSION_CONFLICT', '已被修改', 409),
      requestIdFactory: () => 'r',
    })
    const idempotent = createApiClient({
      fetchImpl: async () =>
        errorResponse('IDEMPOTENCY_CONFLICT', 'IDEMPOTENCY_CONFLICT', '同键不同输入', 409),
      requestIdFactory: () => 'r',
    })

    await expect(conflict.query('/api/x')).rejects.toMatchObject({ type: 'CONCURRENT_CONFLICT' })
    await expect(idempotent.query('/api/x')).rejects.toMatchObject({ type: 'IDEMPOTENCY_CONFLICT' })
  })

  it('未认证与无权限都映射为 FORBIDDEN，但保留后端错误码', async () => {
    const client = createApiClient({
      fetchImpl: async () => errorResponse('FORBIDDEN', 'PERMISSION_DENIED', '无权访问', 403),
      requestIdFactory: () => 'r',
    })

    await expect(client.query('/api/payroll')).rejects.toMatchObject({
      type: 'FORBIDDEN',
      code: 'PERMISSION_DENIED',
    })
  })

  it('网络失败映射为 NETWORK_FAILURE，不能伪装成功或业务拒绝', async () => {
    const client = createApiClient({
      fetchImpl: async () => {
        throw new TypeError('Failed to fetch')
      },
      requestIdFactory: () => 'r',
    })

    await expect(client.query('/api/x')).rejects.toMatchObject({
      type: ApiErrorTypes.NETWORK_FAILURE,
      retryable: true,
    })
  })

  it('非 JSON 错误响应也归入 SYSTEM_FAILURE，且不泄露响应正文', async () => {
    const client = createApiClient({
      fetchImpl: async () => new Response('<html>500</html>', { status: 500 }),
      requestIdFactory: () => 'r',
    })

    await expect(client.query('/api/x')).rejects.toMatchObject({
      type: 'SYSTEM_FAILURE',
      httpStatus: 500,
    })
  })

  it('金额响应字段保持字符串，客户端不做业务计算', async () => {
    const fetchImpl = vi.fn(async () =>
      jsonResponse({ totalPayable: { amount: '1000.00', currency: 'CNY' } }),
    )
    const client = createApiClient({ fetchImpl, requestIdFactory: () => 'r' })

    const result = await client.query<{ totalPayable: { amount: string; currency: string } }>(
      '/api/payroll/1',
    )

    expect(result.totalPayable.amount).toBe('1000.00')
    expect(typeof result.totalPayable.amount).toBe('string')
  })
})
