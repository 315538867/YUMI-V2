import { apiFetch } from './client';

/**
 * 静态数据（任务 2.22/2.25）：类别由系统固定 code，不可增删改名；
 * 星级/包装档位/缝边种类条目用户自建，员工工种仅可改名与启停。
 */
export type StaticDataCode = 'STAR_LEVEL' | 'PACKAGING_TIER' | 'SEAM_TYPE' | 'WORK_TYPE';

export interface StaticDataCategory {
  code: StaticDataCode;
  name: string;
  itemCount: number;
}

export interface StaticDataItem {
  id: number;
  /** 仅员工工种有系统固定 code */
  code?: string;
  name: string;
  /**
   * 星级与包装档位的标准分钟。
   * 注意类型不统一：`star_levels.std_minutes` 为 `int unsigned`（接口返回数字），
   * `packaging_tiers.std_minutes` 为 `decimal(9,3)`（接口返回字符串），故为联合类型。
   */
  stdMinutes?: string | number;
  /** 缝边种类的成本单价（元/件） */
  costPrice?: string;
  /** 仅员工工种有启停 */
  active?: boolean;
}

export interface StaticDataItemRequest {
  name?: string;
  stdMinutes?: string;
  costPrice?: string;
  active?: boolean;
  reason?: string;
}

export function listStaticDataCategories(): Promise<StaticDataCategory[]> {
  return apiFetch<StaticDataCategory[]>('/api/settings/static-data');
}

export function listStaticDataItems(code: StaticDataCode): Promise<StaticDataItem[]> {
  return apiFetch<StaticDataItem[]>(`/api/settings/static-data/${code}`);
}

export function createStaticDataItem(code: StaticDataCode, body: StaticDataItemRequest): Promise<StaticDataItem> {
  return apiFetch<StaticDataItem>(`/api/settings/static-data/${code}/items`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function updateStaticDataItem(
  code: StaticDataCode,
  id: number,
  body: StaticDataItemRequest,
): Promise<StaticDataItem> {
  return apiFetch<StaticDataItem>(`/api/settings/static-data/${code}/items/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  });
}

export function deleteStaticDataItem(code: StaticDataCode, id: number): Promise<void> {
  return apiFetch<void>(`/api/settings/static-data/${code}/items/${id}`, { method: 'DELETE' });
}
