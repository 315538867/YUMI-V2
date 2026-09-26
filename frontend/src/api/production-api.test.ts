import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  cancelProductionTaskItem,
  createProductionTask,
  createReworkSource,
  getProductionTask,
  getProductionTaskFacts,
  listOvertimeTasks,
  listProductionTasks,
  verifyProductionTask,
} from './production';

afterEach(() => {
  vi.unstubAllGlobals();
});

function envelope(data: unknown): Response {
  return new Response(
    JSON.stringify({ code: 'OK', message: '', fieldErrors: [], requestId: 'req-1', data }),
    { status: 200, headers: { 'Content-Type': 'application/json' } },
  );
}

function stubFetch() {
  const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(envelope({ id: 1 })));
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

/**
 * 任务 5.17：`production.ts` 的所有写请求由 API 客户端自动携带 `Idempotency-Key`，
 * 页面不手工拼装；只读 GET 不携带写头。
 */
describe('生产 API 幂等（任务 5.17）', () => {
  it('sendsIdempotencyKeyForWrites', async () => {
    const fetchMock = stubFetch();

    await createProductionTask({
      taskDate: '2026-09-23',
      employeeId: 1,
      workTypeId: 2,
      taskType: 'NORMAL',
      items: [{ orderItemId: 9, plannedQuantity: 3, sourceType: 'ORDER' }],
    });
    await cancelProductionTaskItem(5, 6, '不再执行');
    await verifyProductionTask(5, [{ taskItemId: 6, qualifiedQuantity: 1, reworkQuantity: 0, scrapQuantity: 0 }]);
    await createReworkSource({ originVerificationId: 7, quantity: 2, reason: '返工' });

    expect(fetchMock).toHaveBeenCalledTimes(4);
    for (const [, init] of fetchMock.mock.calls) {
      expect(init.method).toBe('POST');
      expect(init.headers.get('Idempotency-Key')).toBeTruthy();
      expect(init.headers.get('Content-Type')).toBe('application/json');
    }

    // 批量核验只发一个写请求，body 为 items[]
    const verifyCall = fetchMock.mock.calls[2];
    expect(verifyCall[0]).toBe('/api/production-tasks/5/verify');
    expect(JSON.parse(verifyCall[1].body)).toEqual({
      items: [{ taskItemId: 6, qualifiedQuantity: 1, reworkQuantity: 0, scrapQuantity: 0 }],
    });
  });

  it('只读 GET 不携带 Idempotency-Key', async () => {
    const fetchMock = stubFetch();

    await listProductionTasks({ dateFrom: '2026-09-22', dateTo: '2026-09-28' });
    await getProductionTask(3);

    for (const [, init] of fetchMock.mock.calls) {
      expect(init.method ?? 'GET').toBe('GET');
      expect(init.headers.get('Idempotency-Key')).toBeNull();
    }
  });

  it('readsFactsAndOvertimeByDateAsSingleGetEndpoints', async () => {
    const fetchMock = stubFetch();

    await getProductionTaskFacts(7);
    await listOvertimeTasks('2026-09-23');

    expect(fetchMock.mock.calls[0][0]).toBe('/api/production-tasks/7/facts');
    expect(fetchMock.mock.calls[1][0]).toBe('/api/overtime-tasks?taskDate=2026-09-23');
    for (const [, init] of fetchMock.mock.calls) {
      expect(init.method ?? 'GET').toBe('GET');
      expect(init.headers.get('Idempotency-Key')).toBeNull();
    }
  });
});
