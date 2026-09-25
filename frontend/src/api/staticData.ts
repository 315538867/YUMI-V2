import { apiFetch } from './client';

/**
 * 静态数据（任务 2.22/2.25）：类别由系统固定 code，不可增删改名；
 * 星级/包装档位/缝边种类条目用户自建（都只提供整数标准分钟），员工工种仅可改名。
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
  /** 标准分钟（整数，1–360）：星级/包装档位/缝边种类三类同口径 */
  stdMinutes?: string;
}

export interface StaticDataItemRequest {
  name?: string;
  stdMinutes?: string;
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
