/**
 * 类型安全 API 客户端：统一信封 {code,message,fieldErrors,requestId,data} 在此一层解析。
 * 成功（code=OK）解包返回 data；任何非 OK 都抛 YumiApiError，调用处只处理业务提示。
 * 金额一律保持服务端返回的字符串，客户端不做算术、不回写覆盖。
 */
export type Money = string;

import { YumiApiError, type ApiErrorBody } from './errors';

export interface RequestOptions {
  idempotencyKey?: string;
  requestId?: string;
  signal?: AbortSignal;
}

const WRITE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

interface Envelope extends ApiErrorBody {
  data: unknown;
}

function asEnvelope(value: unknown): Envelope | null {
  if (typeof value !== 'object' || value === null) {
    return null;
  }
  const candidate = value as Record<string, unknown>;
  if (typeof candidate.code !== 'string' || typeof candidate.message !== 'string') {
    return null;
  }
  return candidate as unknown as Envelope;
}

async function parseBody(response: Response): Promise<unknown> {
  if (response.status === 204) {
    return undefined;
  }
  const text = await response.text();
  if (!text) {
    return undefined;
  }
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

export async function apiFetch<T>(path: string, init: RequestInit & RequestOptions = {}): Promise<T> {
  const { idempotencyKey, requestId, headers, body, method = 'GET', signal, ...rest } = init;

  const finalHeaders = new Headers(headers);
  finalHeaders.set('Accept', 'application/json');
  if (!finalHeaders.has('X-Request-Id')) {
    finalHeaders.set('X-Request-Id', requestId ?? crypto.randomUUID());
  }
  if (WRITE_METHODS.has(method) && !finalHeaders.has('Idempotency-Key')) {
    finalHeaders.set('Idempotency-Key', idempotencyKey ?? crypto.randomUUID());
  }
  if (body !== undefined && !finalHeaders.has('Content-Type')) {
    finalHeaders.set('Content-Type', 'application/json');
  }

  const response = await fetch(path, {
    ...rest,
    method,
    headers: finalHeaders,
    body,
    signal,
    credentials: 'include',
  });

  if (response.status === 204) {
    return undefined as T;
  }

  const parsed = await parseBody(response);
  const envelope = asEnvelope(parsed);

  if (!response.ok) {
    if (envelope) {
      throw new YumiApiError(envelope, response.status);
    }
    throw new YumiApiError(
      {
        code: 'INTERNAL_ERROR',
        message: `请求失败（HTTP ${response.status}）`,
        fieldErrors: [],
        requestId: response.headers.get('X-Request-Id') ?? '',
      },
      response.status,
    );
  }

  if (envelope) {
    if (envelope.code === 'OK') {
      return envelope.data as T;
    }
    // 业务码非 OK：无论 HTTP 状态一律在封装层抛错，调用处不需要判断接口状态
    throw new YumiApiError(envelope, response.status);
  }

  throw new YumiApiError(
    {
      code: 'INTERNAL_ERROR',
      message: '响应不是统一信封结构',
      fieldErrors: [],
      requestId: response.headers.get('X-Request-Id') ?? '',
    },
    response.status,
  );
}

export function isApiError(value: unknown): value is YumiApiError {
  return value instanceof YumiApiError;
}

export type { ApiErrorBody };
