import { describe, expect, it } from 'vitest';
import { collectPlanLines, type PlanLineDraft } from './planInput';

function line(key: string, patch: Partial<PlanLineDraft> = {}): PlanLineDraft {
  return { key, ...patch };
}

describe('草稿库存计划收集（任务 4.8）', () => {
  it('按明细序号提交批次与数量，不做任何客户端库存计算', () => {
    expect(
      collectPlanLines([line('a', { lineNo: 1, batchId: 7, quantity: 4 }), line('b', { lineNo: 2, batchId: 9, quantity: 3 })], 2),
    ).toEqual([
      { lineNo: 1, batchId: 7, quantity: 4 },
      { lineNo: 2, batchId: 9, quantity: 3 },
    ]);
  });

  it('完全空行忽略，空计划提交为空数组（服务端据此清空计划）', () => {
    expect(collectPlanLines([], 1)).toEqual([]);
    expect(collectPlanLines([line('a')], 1)).toEqual([]);
  });

  it('半填行返回 null，由调用方提示而不是静默丢弃', () => {
    expect(collectPlanLines([line('a', { lineNo: 1, batchId: 7 })], 1)).toBeNull();
    expect(collectPlanLines([line('a', { lineNo: 1, quantity: 2 })], 1)).toBeNull();
    expect(collectPlanLines([line('a', { batchId: 7, quantity: 2 })], 1)).toBeNull();
    expect(collectPlanLines([line('a', { lineNo: 1, batchId: 7, quantity: 0 })], 1)).toBeNull();
  });

  it('明细已被移除的行失效，不阻塞保存', () => {
    expect(collectPlanLines([line('a', { lineNo: 3, batchId: 7, quantity: 2 })], 2)).toEqual([]);
  });
});
