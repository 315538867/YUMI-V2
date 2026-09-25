/**
 * 核验页的即时提示计算（任务 5.15）：只用于输入过程中的提示与提交前拦截，
 * **不是权威口径**——服务端在同一事务内重算当前可执行数量并校验等式。
 */
export interface VerifyInputs {
  completedQuantity?: number | null;
  qualifiedQuantity?: number | null;
  reworkQuantity?: number | null;
  scrapQuantity?: number | null;
}

function value(input?: number | null): number {
  return typeof input === 'number' && Number.isFinite(input) ? input : 0;
}

/** 本次完成 = 合格 + 返工 + 报废。 */
export function equationHolds(inputs: VerifyInputs): boolean {
  return (
    value(inputs.completedQuantity) ===
    value(inputs.qualifiedQuantity) + value(inputs.reworkQuantity) + value(inputs.scrapQuantity)
  );
}

/** 未完成 = 计划数量 − 本次完成；负值表示本次完成超过计划数量。 */
export function incompleteQuantity(planQuantity: number, inputs: VerifyInputs): number {
  return planQuantity - value(inputs.completedQuantity);
}

/** 提交前拦截：等式成立且本次完成不超过计划数量（可执行上限由服务端判定）。 */
export function canSubmit(planQuantity: number, inputs: VerifyInputs): boolean {
  return equationHolds(inputs) && incompleteQuantity(planQuantity, inputs) >= 0;
}
