import { describe, expect, it } from 'vitest';
import { canSubmit, equationHolds, incompleteQuantity } from './verifyMath';

describe('核验页即时提示计算（任务 5.15）', () => {
  it('等式成立：本次完成 = 合格 + 返工 + 报废', () => {
    expect(equationHolds({ completedQuantity: 10, qualifiedQuantity: 8, reworkQuantity: 1, scrapQuantity: 1 })).toBe(true);
    expect(equationHolds({ completedQuantity: 10, qualifiedQuantity: 8, reworkQuantity: 1, scrapQuantity: 0 })).toBe(false);
    // 全为 0 也算成立（零合格核验是合法结果）
    expect(equationHolds({})).toBe(true);
  });

  it('未完成 = 计划数量 − 本次完成，超过计划时为负', () => {
    expect(incompleteQuantity(10, { completedQuantity: 6 })).toBe(4);
    expect(incompleteQuantity(10, { completedQuantity: 10 })).toBe(0);
    expect(incompleteQuantity(10, { completedQuantity: 11 })).toBe(-1);
  });

  it('提交前拦截：等式不成立或超过计划数量都不放行', () => {
    expect(canSubmit(10, { completedQuantity: 6, qualifiedQuantity: 6 })).toBe(true);
    expect(canSubmit(10, { completedQuantity: 6, qualifiedQuantity: 5 })).toBe(false);
    expect(canSubmit(10, { completedQuantity: 11, qualifiedQuantity: 11 })).toBe(false);
  });

  it('缺省与非法值按 0 处理，不产生 NaN 提示', () => {
    expect(equationHolds({ completedQuantity: null, qualifiedQuantity: undefined })).toBe(true);
    expect(incompleteQuantity(10, { completedQuantity: Number.NaN })).toBe(10);
  });
});
