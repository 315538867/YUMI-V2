/**
 * 平台 HTTP 客户端：统一的请求头、幂等键、错误归一化与十进制字符串契约。
 *
 * 规则来源：`docs/一期/05-跨模块接口与事务规则.md` §9、§11，
 * `04-公共业务与技术规则.md` §2、§8。
 *
 * 页面不直接使用本客户端：业务页面只能经模块 `services` 调用，`services` 再使用本客户端。
 */

/** 与后端 `ApiErrorType` 一一对应的稳定错误类型。 */
export const ApiErrorTypes = {
  VALIDATION: 'VALIDATION',
  BUSINESS_REJECTION: 'BUSINESS_REJECTION',
  NOT_FOUND: 'NOT_FOUND',
  FORBIDDEN: 'FORBIDDEN',
  CONCURRENT_CONFLICT: 'CONCURRENT_CONFLICT',
  IDEMPOTENCY_CONFLICT: 'IDEMPOTENCY_CONFLICT',
  SYSTEM_FAILURE: 'SYSTEM_FAILURE',
  /** 仅前端使用：请求未到达服务器或响应未返回。 */
  NETWORK_FAILURE: 'NETWORK_FAILURE',
} as const

export type ApiErrorType = (typeof ApiErrorTypes)[keyof typeof ApiErrorTypes]

const SERVER_ERROR_TYPES: ReadonlySet<string> = new Set([
  ApiErrorTypes.VALIDATION,
  ApiErrorTypes.BUSINESS_REJECTION,
  ApiErrorTypes.NOT_FOUND,
  ApiErrorTypes.FORBIDDEN,
  ApiErrorTypes.CONCURRENT_CONFLICT,
  ApiErrorTypes.IDEMPOTENCY_CONFLICT,
  ApiErrorTypes.SYSTEM_FAILURE,
])

export interface ApiFieldIssue {
  field: string
  reason: string
}

/** 归一化后的接口错误；保留后端稳定类型、错误码与请求编号。 */
export class ApiClientError extends Error {
  readonly type: ApiErrorType

  readonly code: string

  readonly requestId: string | undefined

  readonly httpStatus: number | undefined

  readonly fieldIssues: readonly ApiFieldIssue[]

  readonly retryable: boolean

  constructor(options: {
    type: ApiErrorType
    code: string
    message: string
    requestId?: string | undefined
    httpStatus?: number | undefined
    fieldIssues?: readonly ApiFieldIssue[] | undefined
    retryable?: boolean | undefined
  }) {
    super(options.message)
    this.name = 'ApiClientError'
    this.type = options.type
    this.code = options.code
    this.requestId = options.requestId
    this.httpStatus = options.httpStatus
    this.fieldIssues = options.fieldIssues ?? []
    this.retryable = options.retryable ?? false
  }
}

/** 线上的金额对象：金额值为十进制字符串。 */
export interface MoneyWire {
  amount: string
  currency: string
}

export function isMoneyWire(value: unknown): value is MoneyWire {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const candidate = value as Record<string, unknown>
  return typeof candidate.amount === 'string' && typeof candidate.currency === 'string'
}

const DECIMAL_TEXT = /^-?(0|[1-9][0-9]*)(\.[0-9]+)?$/

/** 校验并返回原始十进制文本；拒绝科学计数法、空白与空字符串。 */
export function toDecimalString(raw: string): string {
  if (!DECIMAL_TEXT.test(raw)) {
    throw new Error(`金额/数值必须是十进制字符串（不接受科学计数法）：${raw}`)
  }
  return raw
}

/**
 * 解析十进制字符串用于只读展示或本地格式化。
 *
 * 禁止用返回的数值参与工资、成本、产能、来源余额、应收或利润计算；权威计算在后端。
 */
export function parseDecimal(raw: string): number {
  return Number(toDecimalString(raw))
}

export interface ApiClientOptions {
  /** 请求路径前缀，默认同源 `/api`。 */
  baseUrl?: string
  fetchImpl?: typeof fetch
  requestIdFactory?: () => string
  /** 会话令牌提供者；有令牌时附加 Bearer 认证头。 */
  tokenProvider?: () => string | null
  /** 会话失效（未认证、会话吊销或过期）时的统一回调，由认证状态清理并跳转登录。 */
  onUnauthorized?: (error: ApiClientError) => void
}

export interface QueryOptions {
  signal?: AbortSignal | undefined
}

export interface CommandOptions<TBody> extends QueryOptions {
  method?: 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: TBody
  /** 正式命令必须显式提供幂等键；同一个用户操作重试时复用同一个键。 */
  idempotencyKey: string
}

export interface ApiClient {
  query<T>(path: string, options?: QueryOptions): Promise<T>
  queryBlob(path: string, options?: QueryOptions): Promise<Blob>
  command<T, TBody = unknown>(path: string, options: CommandOptions<TBody>): Promise<T>
  /** 仅用于登录/退出等平台会话端点；业务命令必须使用 command 并携带幂等键。 */
  sessionCommand<T, TBody = unknown>(path: string, body?: TBody): Promise<T>
}

const DEFAULT_REQUEST_HEADERS = {
  Accept: 'application/json',
  'Content-Type': 'application/json',
} as const

export function createApiClient(options: ApiClientOptions = {}): ApiClient {
  const baseUrl = options.baseUrl ?? ''
  const fetchImpl = options.fetchImpl ?? globalThis.fetch.bind(globalThis)
  const requestIdFactory =
    options.requestIdFactory ??
    (() =>
      globalThis.crypto?.randomUUID?.() ?? `req-${Date.now()}-${Math.random().toString(16).slice(2)}`)
  const tokenProvider = options.tokenProvider

  async function send<T>(
    path: string,
    init: RequestInit,
    requestId: string,
  ): Promise<T> {
    let response: Response
    try {
      const token = tokenProvider?.() ?? null
      response = await fetchImpl(`${baseUrl}${path}`, {
        credentials: 'same-origin',
        ...init,
        headers: {
          ...DEFAULT_REQUEST_HEADERS,
          'X-Request-Id': requestId,
          ...(token === null ? {} : { Authorization: `Bearer ${token}` }),
          ...(init.headers as Record<string, string> | undefined),
        },
      })
    } catch (cause) {
      throw new ApiClientError({
        type: ApiErrorTypes.NETWORK_FAILURE,
        code: 'NETWORK_FAILURE',
        message: '网络请求失败，请检查连接后重试',
        requestId,
        retryable: true,
      })
    }

    if (response.ok) {
      if (response.status === 204) {
        return undefined as T
      }
      return (await response.json()) as T
    }

    const error = await toApiClientError(response, requestId)
    if (isSessionExpired(error)) {
      options.onUnauthorized?.(error)
    }
    throw error
  }

  return {
    async query<T>(path: string, queryOptions: QueryOptions = {}): Promise<T> {
      const requestId = requestIdFactory()
      const init: RequestInit = { method: 'GET' }
      if (queryOptions.signal !== undefined) {
        init.signal = queryOptions.signal
      }
      return send<T>(path, init, requestId)
    },

    async queryBlob(path: string, queryOptions: QueryOptions = {}): Promise<Blob> {
      const requestId = requestIdFactory()
      const init: RequestInit = { method: 'GET' }
      if (queryOptions.signal !== undefined) {
        init.signal = queryOptions.signal
      }
      const response = await fetchWithAuth(path, init, requestId)
      if (!response.ok) {
        const error = await toApiClientError(response, requestId)
        if (isSessionExpired(error)) {
          options.onUnauthorized?.(error)
        }
        throw error
      }
      return response.blob()
    },

    async command<T, TBody = unknown>(
      path: string,
      commandOptions: CommandOptions<TBody>,
    ): Promise<T> {
      if (!commandOptions.idempotencyKey || commandOptions.idempotencyKey.trim() === '') {
        throw new Error('正式命令必须携带幂等键')
      }
      const requestId = requestIdFactory()
      const init: RequestInit = {
        method: commandOptions.method ?? 'POST',
        headers: { 'Idempotency-Key': commandOptions.idempotencyKey },
      }
      if (commandOptions.body !== undefined) {
        init.body = JSON.stringify(commandOptions.body)
      }
      if (commandOptions.signal !== undefined) {
        init.signal = commandOptions.signal
      }
      return send<T>(path, init, requestId)
    },

    async sessionCommand<T, TBody = unknown>(path: string, body?: TBody): Promise<T> {
      const requestId = requestIdFactory()
      const init: RequestInit = { method: 'POST' }
      if (body !== undefined) {
        init.body = JSON.stringify(body)
      }
      return send<T>(path, init, requestId)
    },
  }

  async function fetchWithAuth(
    path: string,
    init: RequestInit,
    requestId: string,
  ): Promise<Response> {
    const token = tokenProvider?.() ?? null
    try {
      return await fetchImpl(`${baseUrl}${path}`, {
        credentials: 'same-origin',
        ...init,
        headers: {
          Accept: 'application/json',
          'X-Request-Id': requestId,
          ...(token === null ? {} : { Authorization: `Bearer ${token}` }),
          ...(init.headers as Record<string, string> | undefined),
        },
      })
    } catch {
      throw new ApiClientError({
        type: ApiErrorTypes.NETWORK_FAILURE,
        code: 'NETWORK_FAILURE',
        message: '网络请求失败，请检查连接后重试',
        requestId,
        retryable: true,
      })
    }
  }
}

const SESSION_EXPIRED_CODES = new Set([
  'AUTHENTICATION_REQUIRED',
  'SESSION_REVOKED',
  'SESSION_EXPIRED',
  'ACCOUNT_DISABLED',
  'ACCOUNT_LOCKED',
])

function isSessionExpired(error: ApiClientError): boolean {
  return error.type === ApiErrorTypes.FORBIDDEN && SESSION_EXPIRED_CODES.has(error.code)
}

async function toApiClientError(response: Response, requestId: string): Promise<ApiClientError> {
  let payload: unknown
  try {
    payload = await response.json()
  } catch {
    return new ApiClientError({
      type: ApiErrorTypes.SYSTEM_FAILURE,
      code: 'SYSTEM_FAILURE',
      message: '服务暂时不可用，请稍后重试',
      requestId,
      httpStatus: response.status,
    })
  }

  if (typeof payload !== 'object' || payload === null) {
    return new ApiClientError({
      type: ApiErrorTypes.SYSTEM_FAILURE,
      code: 'SYSTEM_FAILURE',
      message: '服务暂时不可用，请稍后重试',
      requestId,
      httpStatus: response.status,
    })
  }

  const body = payload as Record<string, unknown>
  const rawType = typeof body.type === 'string' ? body.type : ApiErrorTypes.SYSTEM_FAILURE
  const type = (SERVER_ERROR_TYPES.has(rawType) ? rawType : ApiErrorTypes.SYSTEM_FAILURE) as ApiErrorType
  const issues = Array.isArray(body.fieldIssues)
    ? (body.fieldIssues as ApiFieldIssue[]).filter(
        (issue) => typeof issue?.field === 'string' && typeof issue?.reason === 'string',
      )
    : []

  return new ApiClientError({
    type,
    code: typeof body.code === 'string' ? body.code : type,
    message: typeof body.message === 'string' ? body.message : '请求失败',
    requestId: typeof body.requestId === 'string' ? body.requestId : requestId,
    httpStatus: response.status,
    fieldIssues: issues,
    retryable: type === ApiErrorTypes.CONCURRENT_CONFLICT || type === ApiErrorTypes.SYSTEM_FAILURE,
  })
}
