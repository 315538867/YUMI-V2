import { apiFetch, type Money } from './client';

/**
 * 订单 API（任务 3.2–3.10 的后端契约）：金额与数量一律保持服务端字符串/整数，
 * 客户端不做金额算术、不覆盖服务端派生结果。
 */
export type OrderStatus = 'DRAFT' | 'CONFIRMED' | 'CANCELLED' | 'CLOSED';

/** 五类派生状态（主状态见 order.status），全部由服务端事实计算。 */
export interface DerivedStatus {
  scheduling: string;
  execution: string;
  production: string;
  demandHandling: string;
  shipment: string;
}

export interface OrderSummary {
  id: number;
  orderNo: string;
  customerId: number;
  customerName: string;
  status: OrderStatus;
  orderDate: string;
  expectedDeliveryDate?: string | null;
  receivableAmount: Money;
  version: number;
  derived: DerivedStatus;
}

export interface OrderItemView {
  id: number;
  lineNo: number;
  productId: number;
  productNo: string;
  productName: string;
  quantity: number;
  seamQuantity: number;
  unitPrice: Money;
  goodsAmount: Money;
  seamTypeId?: number | null;
  seamTypeName?: string | null;
  seamUnitCost: Money;
  seamFee: Money;
  seamAmount: Money;
  unitCost: Money;
  goodsCostAmount: Money;
  seamCostAmount: Money;
  note?: string | null;
}

/** 草稿库存计划行（任务 4.8）：按明细序号引用明细，不占用库存。 */
export interface OrderPlanLineView {
  id: number;
  orderItemId: number;
  lineNo: number;
  batchId: number;
  batchNo?: string | null;
  node?: string | null;
  seamState?: string | null;
  batchQuantity: number;
  quantity: number;
}

export interface OrderDetail {
  id: number;
  orderNo: string;
  customerId: number;
  customerName: string;
  status: OrderStatus;
  orderDate: string;
  expectedDeliveryDate?: string | null;
  recipientName: string;
  recipientPhone: string;
  region: string;
  address: string;
  note?: string | null;
  goodsAmount: Money;
  seamAmount: Money;
  discountAmount: Money;
  receivableAmount: Money;
  goodsCostAmount: Money;
  seamCostAmount: Money;
  costAmount: Money;
  profitAmount: Money;
  version: number;
  derived: DerivedStatus;
  items: OrderItemView[];
  inventoryPlan: OrderPlanLineView[];
}

/** 明细入参：缝边数量为 0 即不缝边剪袋，此时缝边种类/收费被服务端忽略。 */
export interface OrderItemRequest {
  productId: number;
  quantity: number;
  seamQuantity: number;
  unitPrice?: Money;
  seamTypeId?: number | null;
  seamFee?: Money;
  note?: string;
}

/** 计划行入参：lineNo 为明细序号（1 起），明细 id 每次保存都会变，序号才稳定。 */
export interface OrderPlanLineRequest {
  lineNo: number;
  batchId: number;
  quantity: number;
}

export interface OrderWriteRequest {
  customerId: number;
  orderDate: string;
  expectedDeliveryDate?: string | null;
  recipientName?: string;
  recipientPhone?: string;
  region?: string;
  address?: string;
  note?: string;
  discountAmount?: Money;
  items: OrderItemRequest[];
  /** 非空列表整体替换草稿计划；空列表清空计划；不传则保持原值。 */
  inventoryPlan?: OrderPlanLineRequest[];
}

export interface FulfillmentItem {
  orderItemId: number;
  lineNo: number;
  productNo: string;
  productName: string;
  quantity: number;
  seamQuantity: number;
  noSeamRequired: number;
  makingRequired: number;
  packingRequired: number;
  seamRequired: number;
  finalRequired: number;
  makingInflow: number;
  packingInflow: number;
  seamInflow: number;
  makingPlanned: number;
  packingPlanned: number;
  seamPlanned: number;
  verifiedProcessed: number;
  reworkPending: number;
  remakePending: number;
  shippable: number;
  shipped: number;
  finishedSurplus: number;
  undelivered: number;
  derived: DerivedStatus;
}

export interface FulfillmentView {
  orderId: number;
  orderNo: string;
  status: OrderStatus;
  derived: DerivedStatus;
  items: FulfillmentItem[];
}

export type ChangeType = 'ADD' | 'UPDATE' | 'REMOVE';

export interface ChangeItemView {
  id: number;
  orderItemId?: number | null;
  changeType: ChangeType;
  lineNo?: number | null;
  productId?: number | null;
  beforeQuantity?: number | null;
  afterQuantity?: number | null;
  beforeSeamQuantity?: number | null;
  afterSeamQuantity?: number | null;
  beforeUnitPrice?: Money | null;
  afterUnitPrice?: Money | null;
  beforeSeamTypeId?: number | null;
  afterSeamTypeId?: number | null;
  beforeSeamFee?: Money | null;
  afterSeamFee?: Money | null;
  beforeNote?: string | null;
  afterNote?: string | null;
  surplusDisposition?: string | null;
  surplusQuantity?: number | null;
  surplusReason?: string | null;
}

export interface ChangeView {
  id: number;
  changeNo: string;
  orderId: number;
  orderNo: string;
  status: 'DRAFT' | 'CONFIRMED';
  reason?: string | null;
  newExpectedDeliveryDate?: string | null;
  newRecipientName?: string | null;
  newRecipientPhone?: string | null;
  newRegion?: string | null;
  newAddress?: string | null;
  newNote?: string | null;
  newDiscountAmount?: Money | null;
  version: number;
  items: ChangeItemView[];
}

export interface ChangeItemRequest {
  orderItemId?: number | null;
  remove?: boolean;
  productId?: number | null;
  quantity?: number | null;
  seamQuantity?: number | null;
  unitPrice?: Money | null;
  seamTypeId?: number | null;
  seamFee?: Money | null;
  note?: string | null;
  surplusDisposition?: string | null;
  surplusQuantity?: number | null;
  surplusReason?: string | null;
}

export interface ChangeWriteRequest {
  reason?: string;
  expectedDeliveryDate?: string | null;
  recipientName?: string;
  recipientPhone?: string;
  region?: string;
  address?: string;
  note?: string;
  discountAmount?: Money;
  items?: ChangeItemRequest[];
}

export interface ChangeConfirmResult {
  change: ChangeView;
  order: OrderDetail;
}

export function listOrders(params: {
  status?: string;
  customerId?: number;
  orderDateFrom?: string;
  orderDateTo?: string;
} = {}): Promise<OrderSummary[]> {
  const query = new URLSearchParams();
  if (params.status) query.set('status', params.status);
  if (params.customerId) query.set('customerId', String(params.customerId));
  if (params.orderDateFrom) query.set('orderDateFrom', params.orderDateFrom);
  if (params.orderDateTo) query.set('orderDateTo', params.orderDateTo);
  return apiFetch<OrderSummary[]>(`/api/orders?${query.toString()}`);
}

export function getOrder(id: number | string): Promise<OrderDetail> {
  return apiFetch<OrderDetail>(`/api/orders/${id}`);
}

export function createOrder(body: OrderWriteRequest): Promise<OrderDetail> {
  return apiFetch<OrderDetail>('/api/orders', { method: 'POST', body: JSON.stringify(body) });
}

export function updateOrder(
  id: number | string,
  body: Partial<OrderWriteRequest> & { version: number; clearExpectedDeliveryDate?: boolean; reason?: string },
): Promise<OrderDetail> {
  return apiFetch<OrderDetail>(`/api/orders/${id}`, { method: 'PATCH', body: JSON.stringify(body) });
}

export function confirmOrder(
  id: number | string,
  options: { transferShortageToProduction?: boolean } = {},
): Promise<OrderDetail> {
  return apiFetch<OrderDetail>(`/api/orders/${id}/confirm`, {
    method: 'POST',
    body: JSON.stringify(
      options.transferShortageToProduction ? { transferShortageToProduction: true } : {},
    ),
  });
}

export function cancelOrder(id: number | string, reason: string): Promise<OrderDetail> {
  return apiFetch<OrderDetail>(`/api/orders/${id}/cancel`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}

export function getFulfillment(id: number | string): Promise<FulfillmentView> {
  return apiFetch<FulfillmentView>(`/api/orders/${id}/fulfillment`);
}

export function listChangeOrders(orderId: number | string): Promise<ChangeView[]> {
  return apiFetch<ChangeView[]>(`/api/orders/${orderId}/change-orders`);
}

export function createChangeOrder(orderId: number | string, body: ChangeWriteRequest): Promise<ChangeView> {
  return apiFetch<ChangeView>(`/api/orders/${orderId}/change-orders`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function getChangeOrder(id: number | string): Promise<ChangeView> {
  return apiFetch<ChangeView>(`/api/order-changes/${id}`);
}

export function updateChangeOrder(id: number | string, body: ChangeWriteRequest): Promise<ChangeView> {
  return apiFetch<ChangeView>(`/api/order-changes/${id}`, { method: 'PATCH', body: JSON.stringify(body) });
}

export function confirmChangeOrder(id: number | string): Promise<ChangeConfirmResult> {
  return apiFetch<ChangeConfirmResult>(`/api/order-changes/${id}/confirm`, {
    method: 'POST',
    body: JSON.stringify({}),
  });
}

export const ORDER_STATUS_LABELS: Record<OrderStatus, string> = {
  DRAFT: '草稿',
  CONFIRMED: '已确认',
  CANCELLED: '已取消',
  CLOSED: '已关闭',
};

export const ORDER_STATUS_COLORS: Record<OrderStatus, string> = {
  DRAFT: 'default',
  CONFIRMED: 'blue',
  CANCELLED: 'default',
  CLOSED: 'default',
};
