import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiFetch } from './client';
import { YumiApiError } from './errors';

afterEach(() => {
  vi.unstubAllGlobals();
});

function envelope(data: unknown, overrides: Record<string, unknown> = {}): Response {
  return new Response(
    JSON.stringify({
      code: 'OK',
      message: '',
      fieldErrors: [],
      requestId: 'req-1',
      data,
      ...overrides,
    }),
    { status: 200, headers: { 'Content-Type': 'application/json' } },
  );
}

describe('类型安全 API 客户端（统一信封解包）', () => {
  it('成功信封解包后返回 data，且金额保持字符串', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      envelope({ orderNo: 'YM00001', amount: '12.3400' }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const result = await apiFetch<{ orderNo: string; amount: string }>('/api/orders/1');

    expect(result.orderNo).toBe('YM00001');
    expect(result.amount).toBe('12.3400');
    expect(typeof result.amount).toBe('string');
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/orders/1');
    expect(init.credentials).toBe('include');
    expect(init.headers.get('X-Request-Id')).toBeTruthy();
  });

  it('写命令自动携带 Idempotency-Key', async () => {
    const fetchMock = vi.fn().mockResolvedValue(envelope({ ok: true }));
    vi.stubGlobal('fetch', fetchMock);

    await apiFetch('/api/orders', { method: 'POST', body: JSON.stringify({ note: 'x' }) });

    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers.get('Idempotency-Key')).toBeTruthy();
    expect(init.headers.get('Content-Type')).toBe('application/json');
  });

  it('错误信封（data=null）在封装层统一抛 YumiApiError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({
              code: 'STATE_NOT_EDITABLE',
              message: '当前状态不可编辑',
              fieldErrors: [],
              requestId: 'req-7',
              data: null,
            }),
            { status: 409, headers: { 'Content-Type': 'application/json' } },
          ),
        ),
      ),
    );

    const rejection = await apiFetch('/api/orders/1', { method: 'PATCH' }).catch(error => error);
    expect(rejection).toBeInstanceOf(YumiApiError);
    expect(rejection).toMatchObject({
      code: 'STATE_NOT_EDITABLE',
      status: 409,
      requestId: 'req-7',
    });
    expect((rejection as YumiApiError).fieldErrors).toEqual([]);
  });

  it('HTTP 200 但业务码非 OK 也按错误处理（调用处无需判断状态）', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({
              code: 'CONFLICT_DUPLICATE',
              message: '存在重复记录',
              fieldErrors: [],
              requestId: 'req-8',
              data: null,
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          ),
        ),
      ),
    );

    await expect(apiFetch('/api/products', { method: 'POST' })).rejects.toMatchObject({
      code: 'CONFLICT_DUPLICATE',
    });
  });

  it('非信封错误归一为 INTERNAL_ERROR', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>gateway</html>', { status: 502 })));

    await expect(apiFetch('/api/orders')).rejects.toMatchObject({ code: 'INTERNAL_ERROR' });
  });

  it('204 无体返回 undefined', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })));

    await expect(apiFetch('/api/session', { method: 'DELETE' })).resolves.toBeUndefined();
  });
});
