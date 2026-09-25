import { apiFetch } from './client';

/**
 * 发货 API（阶段六后端契约）：草稿不占数量；确认、作废、更正必须幂等。
 * 物流字段只在发货详情/打印展示，业务导出不包含。
 */
export type ShipmentStatus = 'DRAFT' | 'CONFIRMED' | 'VOIDED';

export interface ShipmentSourceLinkView {
  id: number;
  sourceType: string;
  sourceId: number;
  sourceLineId: number;
  quantity: number;
}

export interface ShipmentLogisticsChangeView {
  id: number;
  beforeCarrier?: string | null;
  afterCarrier?: string | null;
  beforeTrackingNo?: string | null;
  afterTrackingNo?: string | null;
  beforeFreight?: string | null;
  afterFreight?: string | null;
  beforeNote?: string | null;
  afterNote?: string | null;
  reason: string;
}

export interface ShipmentItemView {
  id: number;
  orderItemId: number;
  lineNo: number;
  productNo: string;
  productName: string;
  quantity: number;
  recipientName?: string | null;
  recipientPhone?: string | null;
  region?: string | null;
  address?: string | null;
  cumulativeShippedQuantity?: number | null;
  undeliveredQuantity?: number | null;
  sourceLinks: ShipmentSourceLinkView[];
}

export interface ShipmentView {
  id: number;
  shipmentNo: string;
  orderId: number;
  status: ShipmentStatus;
  shipmentDate: string;
  carrier?: string | null;
  trackingNo?: string | null;
  freight: string;
  logisticsNote?: string | null;
  currentCarrier?: string | null;
  currentTrackingNo?: string | null;
  currentFreight: string;
  currentLogisticsNote?: string | null;
  note?: string | null;
  confirmedBy?: string | null;
  voidReason?: string | null;
  replacesShipmentId?: number | null;
  /** 该批次是否为售后补发批次（补发品不作为订单发货台账，也不能作为售后来源）。 */
  afterSalesReplacement?: boolean;
  version: number;
  items: ShipmentItemView[];
  logisticsChanges: ShipmentLogisticsChangeView[];
}

export interface ShipmentDraftRequest {
  shipmentDate: string;
  carrier?: string;
  trackingNo?: string;
  freight?: string;
  logisticsNote?: string;
  note?: string;
  items: { orderItemId: number; quantity: number }[];
}

export function listShipments(orderId: number | string): Promise<ShipmentView[]> {
  return apiFetch<ShipmentView[]>(`/api/orders/${orderId}/shipments`);
}

export function createShipmentDraft(orderId: number | string, body: ShipmentDraftRequest): Promise<ShipmentView> {
  return apiFetch<ShipmentView>(`/api/orders/${orderId}/shipments`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function updateShipmentDraft(
  orderId: number | string,
  shipmentId: number | string,
  body: ShipmentDraftRequest,
): Promise<ShipmentView> {
  return apiFetch<ShipmentView>(`/api/orders/${orderId}/shipments/${shipmentId}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  });
}

export function confirmShipment(orderId: number | string, shipmentId: number | string): Promise<ShipmentView> {
  return apiFetch<ShipmentView>(`/api/orders/${orderId}/shipments/${shipmentId}/confirm`, {
    method: 'POST',
  });
}

export function changeShipmentLogistics(
  orderId: number | string,
  shipmentId: number | string,
  body: { carrier?: string; trackingNo?: string; freight?: string; logisticsNote?: string; reason: string },
): Promise<ShipmentView> {
  return apiFetch<ShipmentView>(`/api/orders/${orderId}/shipments/${shipmentId}/logistics`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  });
}

export function voidShipment(
  orderId: number | string,
  shipmentId: number | string,
  reason: string,
): Promise<ShipmentView> {
  return apiFetch<ShipmentView>(`/api/orders/${orderId}/shipments/${shipmentId}/void`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}

export function correctShipment(
  orderId: number | string,
  shipmentId: number | string,
  reason: string,
): Promise<ShipmentView> {
  return apiFetch<ShipmentView>(`/api/orders/${orderId}/shipments/${shipmentId}/corrections`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}

export const SHIPMENT_STATUS_LABELS: Record<ShipmentStatus, string> = {
  DRAFT: '草稿',
  CONFIRMED: '已确认',
  VOIDED: '已作废',
};

export const SHIPMENT_STATUS_COLORS: Record<ShipmentStatus, string> = {
  DRAFT: 'default',
  CONFIRMED: 'green',
  VOIDED: 'red',
};

export const SOURCE_TYPE_LABELS: Record<string, string> = {
  INVENTORY_ALLOCATION: '库存领用',
  PRODUCTION_QUALIFIED: '生产合格',
  FINISHED_SURPLUS: '成品余量',
};
