import type { OrderPlanLineRequest } from '../../api/orders';

/** 页面持有的计划行草稿：字段可半填，提交前由 collectPlanLines 归一。 */
export interface PlanLineDraft {
  key: string;
  lineNo?: number;
  batchId?: number;
  quantity?: number;
}

/**
 * 收集计划行（任务 4.8）：lineNo 用明细序号而非明细 id——草稿明细整单替换，id 每次保存都会变。
 * 完全空行忽略；明细已被移除（序号超出范围）的行随之失效；半填行返回 null 由调用方提示，不静默丢弃。
 */
export function collectPlanLines(
  plan: PlanLineDraft[],
  itemCount: number,
): OrderPlanLineRequest[] | null {
  const lines: OrderPlanLineRequest[] = [];
  for (const line of plan) {
    if (!line.lineNo && !line.batchId && !line.quantity) {
      continue;
    }
    if (!line.lineNo || !line.batchId || !line.quantity || line.quantity < 1) {
      return null;
    }
    if (line.lineNo > itemCount) {
      continue;
    }
    lines.push({ lineNo: line.lineNo, batchId: line.batchId, quantity: line.quantity });
  }
  return lines;
}
