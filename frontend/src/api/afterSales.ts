import { apiFetch } from './client';

/**
 * 售后 API（阶段八后端契约）：售后独立于原订单履约；
 * 可补发/已补发从售后台账汇总派生，补发确认后才增加已补发。
 */
export interface AfterSalesItemView {
  id: number;
  orderItemId: number;
  shipmentItemId: number;
  shipmentNo?: string | null;
  productNo: string;
  productName: string;
  seamQuantity: number;
  acceptedQuantity: number;
  returnedQuantity: number;
  replacementRequiredQuantity: number;
  reworkQuantity?: number | null;
  scrapQuantity?: number | null;
  returnVerified: boolean;
  availableQuantity: number;
  shippedQuantity: number;
  pendingQuantity: number;
}

export interface AfterSalesCorrectionView {
  id: number;
  targetId: number;
  beforeValue?: string | null;
  afterValue?: string | null;
  reason?: string | null;
}

export interface AfterSalesCaseView {
  id: number;
  caseNo: string;
  orderId: number;
  caseType: 'REWORK' | 'REPLACEMENT' | 'REWORK_AND_REPLACEMENT';
  status: 'OPEN' | 'COMPLETED' | 'CANCELLED';
  problem: string;
  solution?: string | null;
  note?: string | null;
  items: AfterSalesItemView[];
  refunds: Record<string, unknown>[];
  corrections: AfterSalesCorrectionView[];
  replacementShipmentIds: number[];
}

export function listAfterSales(orderId: number | string): Promise<AfterSalesCaseView[]> {
  return apiFetch<AfterSalesCaseView[]>(`/api/orders/${orderId}/after-sales`);
}

export function createAfterSales(
  orderId: number | string,
  body: {
    caseType: string;
    problem: string;
    solution?: string;
    note?: string;
    items: {
      shipmentItemId: number;
      acceptedQuantity: number;
      returnedQuantity?: number;
      replacementRequiredQuantity?: number;
    }[];
  },
): Promise<AfterSalesCaseView> {
  return apiFetch<AfterSalesCaseView>(`/api/orders/${orderId}/after-sales`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function verifyAfterSalesReturn(
  caseId: number | string,
  body: { afterSalesItemId: number; returnedQuantity: number; reworkQuantity: number; scrapQuantity: number; reason?: string },
): Promise<AfterSalesCaseView> {
  return apiFetch<AfterSalesCaseView>(`/api/after-sales/${caseId}/verify-return`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function allocateAfterSalesInventory(body: {
  afterSalesItemId: number;
  batchId: number;
  quantity: number;
  reason?: string;
}): Promise<{ id: number; movementNo: string; movementType: string }> {
  return apiFetch<{ id: number; movementNo: string; movementType: string }>(
    '/api/inventory/after-sales-allocations',
    { method: 'POST', body: JSON.stringify(body) },
  );
}

export function createReplacementShipment(
  caseId: number | string,
  body: { items: { afterSalesItemId: number; quantity: number }[] },
): Promise<AfterSalesCaseView> {
  return apiFetch<AfterSalesCaseView>(`/api/after-sales/${caseId}/replacement-shipments`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function confirmReplacementShipment(
  caseId: number | string,
  shipmentId: number | string,
): Promise<AfterSalesCaseView> {
  return apiFetch<AfterSalesCaseView>(`/api/after-sales/${caseId}/replacement-shipments/${shipmentId}/confirm`, {
    method: 'POST',
  });
}

export function correctAfterSales(
  caseId: number | string,
  body: { targetType: string; targetId: number; beforeValue?: string; afterValue?: string; reason: string },
): Promise<AfterSalesCaseView> {
  return apiFetch<AfterSalesCaseView>(`/api/after-sales/${caseId}/corrections`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/** 售后生产来源（任务 8.4/8.5）：售后返工与售后补发生产的额度与占用。 */
export interface AfterSalesSourceView {
  id: number;
  afterSalesItemId: number;
  purpose: 'REWORK' | 'REPLACEMENT';
  node: string;
  totalQuantity: number;
  arrangedQuantity: number;
  balance: number;
  reason?: string | null;
  version: number;
}

export function listAfterSalesProductionSources(
  caseId: number | string,
): Promise<AfterSalesSourceView[]> {
  return apiFetch<AfterSalesSourceView[]>(`/api/after-sales/${caseId}/production-sources`);
}

export function createAfterSalesProductionPlan(
  caseId: number | string,
  body: {
    afterSalesItemId: number;
    purpose: 'REWORK' | 'REPLACEMENT';
    node: string;
    planDate: string;
    employeeId: number;
    quantity: number;
    note?: string;
  },
): Promise<{ id: number; planNo: string; planType: string }> {
  return apiFetch<{ id: number; planNo: string; planType: string }>(
    `/api/after-sales/${caseId}/production-sources/plans`,
    { method: 'POST', body: JSON.stringify(body) },
  );
}

export const AFTER_SALES_SOURCE_PURPOSE_LABELS: Record<string, string> = {
  REWORK: '售后返工',
  REPLACEMENT: '售后补发生产',
};

export const AFTER_SALES_TYPE_LABELS: Record<string, string> = {
  REWORK: '返工',
  REPLACEMENT: '补发',
  REWORK_AND_REPLACEMENT: '返工并补发',
};

export const AFTER_SALES_STATUS_LABELS: Record<string, string> = {
  OPEN: '处理中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
};
