import { apiFetch, type Money } from './client';

export interface SettingsValues {
  glueUnitPrice: Money;
  colorpasteUnitPrice: Money;
  /** 百分比数值（如 15.500000 表示 15.5%），与商品表单 lossRatePercent 同口径 */
  lossRateDefault: string;
  boxLaborDefault: Money;
  transportPackingDefault: Money;
  sundriesDefault: Money;
  rentUtilitiesDefault: Money;
  /** 包装提成默认值（元/件）：商品表单取此值作默认、可修改 */
  packagingCommissionDefault: Money;
  /** 时薪（元/小时）：制品/包装/缝边三类人工费的统一派生基数 */
  hourlyWage: Money;
  /** 工作日小时数（小时/天）：制品日薪 = 时薪 × 该值；工作日标准数量按该值折算 */
  workdayHours: Money;
  /** 制品有效工时率（0–1 比例，如 0.750000）：制品人工费按有效工时产量计 */
  makingEffectiveHourRate: string;
  /** 目标利润率（百分数，如 30.000000 表示 30%）：参考售价 = 单件总成本 ÷ (1 − 该率) */
  targetMarginRate: string;
}

export interface StarLevel {
  id: number;
  name: string;
  stdMinutes: number;
}

export interface PackagingTier {
  id: number;
  tierName: string;
  /** 标准分钟（整数） */
  stdMinutes: number;
}

export interface SettingsView {
  values: SettingsValues;
  starLevels: StarLevel[];
  packagingTiers: PackagingTier[];
}

export function getSettings(): Promise<SettingsView> {
  return apiFetch<SettingsView>('/api/settings');
}

export function patchSettings(patch: Partial<SettingsValues> & { reason?: string }): Promise<SettingsValues> {
  return apiFetch<SettingsValues>('/api/settings', { method: 'PATCH', body: JSON.stringify(patch) });
}

/* ---------------- 只读公式说明（任务 2.20） ---------------- */

/** 公式目录条目：稳定标识、输入与单位、表达式、舍入规则、结果含义与固定示例。 */
export interface FormulaEntry {
  identifier: string;
  name: string;
  inputs: string;
  expression: string;
  rounding: string;
  resultMeaning: string;
  example: string;
  codeLocation: string;
  testId: string;
}

export interface FormulaGroup {
  category: string;
  formulas: FormulaEntry[];
}

export interface FormulaCatalogView {
  groups: FormulaGroup[];
}

/** 只读查询：GET 不要求幂等键，不产生业务写入；前端不复制任何计算逻辑。 */
export function getFormulas(): Promise<FormulaCatalogView> {
  return apiFetch<FormulaCatalogView>('/api/settings/formulas');
}
