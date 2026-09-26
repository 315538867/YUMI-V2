import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import type { ReactElement } from 'react';
import { App } from 'antd';
import { VerifyBatchFailureAlert, VerifyItemInputs } from './ProductionTaskVerifyPage';
import { verifyProductionTask } from '../../api/production';
import type { ProductionTaskItemView } from '../../api/production';
import { YumiApiError } from '../../api/errors';
import {
  describeVerifyFailure,
  mapFieldErrorsToItems,
  verifyItemBlocker,
  verifySubmitItems,
} from './productionLogic';

afterEach(() => {
  vi.unstubAllGlobals();
});

function item(overrides: Partial<ProductionTaskItemView> = {}): ProductionTaskItemView {
  return {
    id: 101,
    itemNo: 1,
    orderId: 1,
    orderNo: 'YM00001',
    orderItemId: 11,
    lineNo: 1,
    productId: 21,
    productNo: 'P0001',
    productName: '草莓熊',
    node: 'MAKING',
    plannedQuantity: 10,
    sourceType: 'ORDER',
    sourceId: 11,
    standardMinutes: 6,
    estimatedMinutes: 60,
    actualInflow: 5,
    verifiedProcessedQuantity: 0,
    executableQuantity: 5,
    waitingUpstream: false,
    status: 'PENDING',
    version: 0,
    ...overrides,
  };
}

function renderInputs(node: ReactElement): string {
  return renderToStaticMarkup(<App>{node}</App>);
}

describe('批量核验页（任务 5.18）', () => {
  it('submitsAllItemsOnce', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({ code: 'OK', message: '', fieldErrors: [], requestId: 'req-1', data: { taskId: 9, items: [] } }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    );
    vi.stubGlobal('fetch', fetchMock);

    const payload = verifySubmitItems([
      { item: item({ id: 101 }), input: { qualifiedQuantity: 3, reworkQuantity: 1, scrapQuantity: 1 } },
      { item: item({ id: 102 }), input: { qualifiedQuantity: 0, reworkQuantity: 0, scrapQuantity: 0 } },
    ]);
    await verifyProductionTask(9, payload);

    // 一次点击只发送一个写请求，body 含全部明细
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/production-tasks/9/verify');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body)).toEqual({
      items: [
        { taskItemId: 101, qualifiedQuantity: 3, reworkQuantity: 1, scrapQuantity: 1, note: undefined },
        { taskItemId: 102, qualifiedQuantity: 0, reworkQuantity: 0, scrapQuantity: 0, note: undefined },
      ],
    });
  });

  it('rendersFieldErrorsByItem', () => {
    const located = mapFieldErrorsToItems([
      { field: 'items[1].qualifiedQuantity', message: '当前可执行 5，本次完成 6' },
      { field: 'items[0].scrapQuantity', message: '数量必填且不得为负' },
    ]);
    expect(located).toEqual([
      { itemIndex: 1, field: 'qualifiedQuantity', message: '当前可执行 5，本次完成 6' },
      { itemIndex: 0, field: 'scrapQuantity', message: '数量必填且不得为负' },
    ]);

    const html = renderInputs(
      <VerifyItemInputs
        item={item()}
        input={{ qualifiedQuantity: 6 }}
        errors={located.filter(entry => entry.itemIndex === 0)}
        onChange={() => undefined}
      />,
    );
    expect(html).toContain('本明细字段错误');
    expect(html).toContain('数量必填且不得为负');
    expect(html).toContain('scrapQuantity');
  });

  it('showsAtomicRollbackError', () => {
    const error = new YumiApiError(
      { code: 'QUANTITY_NOT_EXECUTABLE', message: '超过当前可执行数量', fieldErrors: [], requestId: 'req-9' },
      409,
    );
    const failure = describeVerifyFailure(error);
    expect(failure).toContain('未产生任何事实');
    expect(failure).toContain('QUANTITY_NOT_EXECUTABLE');

    const html = renderInputs(<VerifyBatchFailureAlert failure={failure} />);
    expect(html).toContain('整批核验失败');
    expect(html).toContain('未产生任何事实');
  });

  it('blocksQuantityAboveExecutable', () => {
    // 服务端返回的当前可执行上限为 5，本次完成 6 必须被拦截
    const serverItem = item({ executableQuantity: 5, plannedQuantity: 10 });
    const blocker = verifyItemBlocker(serverItem, { qualifiedQuantity: 6 });
    expect(blocker).toContain('超过当前可执行上限 5');

    const html = renderInputs(
      <VerifyItemInputs item={serverItem} input={{ qualifiedQuantity: 6 }} onChange={() => undefined} />,
    );
    expect(html).toContain('当前可执行上限 5');
    expect(html).toContain('超过当前可执行上限 5');
  });

  it('rendersPrototypeVerifyFieldsAndResultPreview', () => {
    const html = renderInputs(
      <VerifyItemInputs
        item={item()}
        input={{ qualifiedQuantity: 3, reworkQuantity: 1, scrapQuantity: 1 }}
        onChange={() => undefined}
      />,
    );
    // 三列字段（合格/返工/报废）+ 结果预览（12px 灰标签 + 22px 粗数字）
    expect(html).toContain('class="verify-fields"');
    expect(html).toContain('class="result-preview"');
    expect(html).toContain('data-testid="verify-preview-101"');
    expect(html).toContain('本次完成');
    expect(html).toContain('合格');
    expect(html).toContain('返工');
    expect(html).toContain('报废');
    // 三列字段（原型 .verify-fields）
    expect(html).toContain('grid-template-columns:repeat(3, 1fr)');
  });
});
