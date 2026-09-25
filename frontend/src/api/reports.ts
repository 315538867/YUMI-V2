import { apiFetch } from './client';

/**
 * 报表 API（阶段九后端契约）：只读，筛选与分页由服务端执行；导出为 CSV 附件。
 */
export interface ReportColumn {
  key: string;
  title: string;
}

export interface ReportPage {
  type: string;
  name: string;
  columns: ReportColumn[];
  rows: Record<string, string | number | null>[];
  page: number;
  size: number;
  total: number;
}

export interface ConsistencyCheck {
  name: string;
  consistent: boolean;
  detail: string;
}

export interface ConsistencyReport {
  checks: ConsistencyCheck[];
  allConsistent: boolean;
}

export const REPORT_TYPES = [
  { value: 'ORDER_FULFILLMENT', label: '订单履约台账' },
  { value: 'INVENTORY', label: '库存台账' },
  { value: 'PRODUCTION', label: '生产台账' },
  { value: 'SHIPMENT', label: '发货台账' },
  { value: 'SETTLEMENT', label: '收退款台账' },
  { value: 'AFTER_SALES', label: '售后台账' },
] as const;

export function queryReport(
  type: string,
  params: { dateFrom?: string; dateTo?: string; orderId?: number; page?: number; size?: number } = {},
): Promise<ReportPage> {
  const query = new URLSearchParams();
  if (params.dateFrom) query.set('dateFrom', params.dateFrom);
  if (params.dateTo) query.set('dateTo', params.dateTo);
  if (params.orderId) query.set('orderId', String(params.orderId));
  if (params.page) query.set('page', String(params.page));
  if (params.size) query.set('size', String(params.size));
  return apiFetch<ReportPage>(`/api/reports/${type}?${query.toString()}`);
}

/** 导出为 CSV 附件：直接下载（导出不套统一信封，返回 text/csv 文件）。 */
export function reportExportUrl(
  type: string,
  params: { dateFrom?: string; dateTo?: string; orderId?: number } = {},
): string {
  const query = new URLSearchParams();
  if (params.dateFrom) query.set('dateFrom', params.dateFrom);
  if (params.dateTo) query.set('dateTo', params.dateTo);
  if (params.orderId) query.set('orderId', String(params.orderId));
  return `/api/reports/${type}/export?${query.toString()}`;
}

export function getConsistency(): Promise<ConsistencyReport> {
  return apiFetch<ConsistencyReport>('/api/reports/consistency');
}
