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
}

export interface StarLevel {
  id: number;
  name: string;
  stdMinutes: number;
}

export interface PackagingTier {
  id: number;
  tierName: string;
  stdMinutes: string;
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

export function createStarLevel(body: { name: string; stdMinutes: number }): Promise<StarLevel> {
  return apiFetch<StarLevel>('/api/settings/star-levels', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function patchStarLevel(
  id: number,
  body: { name?: string; stdMinutes?: number },
): Promise<StarLevel> {
  return apiFetch<StarLevel>(`/api/settings/star-levels/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  });
}

export function deleteStarLevel(id: number): Promise<void> {
  return apiFetch<void>(`/api/settings/star-levels/${id}`, { method: 'DELETE' });
}

export function listPackagingTiers(): Promise<PackagingTier[]> {
  return apiFetch<PackagingTier[]>('/api/settings/packaging-tiers');
}

export function createPackagingTier(body: {
  tierName: string;
  stdMinutes: string;
}): Promise<PackagingTier> {
  return apiFetch<PackagingTier>('/api/settings/packaging-tiers', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function updatePackagingTier(
  id: number,
  body: { tierName: string; stdMinutes: string },
): Promise<PackagingTier> {
  return apiFetch<PackagingTier>(`/api/settings/packaging-tiers/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  });
}

export function deletePackagingTier(id: number): Promise<void> {
  return apiFetch<void>(`/api/settings/packaging-tiers/${id}`, { method: 'DELETE' });
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
