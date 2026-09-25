import { apiFetch } from './client';

/**
 * 库存 API（任务 4.2–4.10 的后端契约）：数量为整数，页面不做库存算术。
 */
export type InventoryNode = 'MAKING' | 'PACKING_BAG' | 'SEAM_CUTTING' | 'SHIPPABLE';
export type SeamState = 'NONE' | 'DONE';

export interface InventoryBatchView {
  id: number;
  batchNo: string;
  productId: number;
  productNo: string;
  productName: string;
  sourceType: string;
  node: InventoryNode;
  seamState: SeamState;
  quantity: number;
  inventoryDate: string;
  note?: string | null;
  version: number;
}

export interface InventorySummaryView {
  productId: number;
  productNo: string;
  productName: string;
  node: InventoryNode;
  seamState: SeamState;
  quantity: number;
  batchCount: number;
}

export interface MovementLineView {
  id: number;
  batchId: number;
  batchNo?: string | null;
  direction: 'IN' | 'OUT';
  quantity: number;
  quantityBefore: number;
  quantityAfter: number;
  productId: number;
  productNo?: string | null;
  productName?: string | null;
  node: InventoryNode;
  seamState: SeamState;
  orderItemId?: number | null;
  orderLineNo?: number | null;
  orderNo?: string | null;
  note?: string | null;
}

export interface MovementView {
  id: number;
  movementNo: string;
  movementType: string;
  businessDate: string;
  sourceType: string;
  reversesMovementId?: number | null;
  reason?: string | null;
  operatorUsername?: string | null;
  note?: string | null;
  lines: MovementLineView[];
}

export interface RecommendationView {
  batchId: number;
  batchNo: string;
  productId: number;
  productNo: string;
  productName: string;
  node: InventoryNode;
  seamState: SeamState;
  quantity: number;
  inventoryDate: string;
  allowedTargets: InventoryNode[];
}

export interface AllocationLineView {
  id: number;
  batchId: number;
  batchNo?: string | null;
  orderItemId: number;
  orderLineNo?: number | null;
  orderNo?: string | null;
  quantity: number;
  targetNode: InventoryNode;
  movementLineId: number;
  quantityBefore: number;
  quantityAfter: number;
  fulfillmentEntryId: number;
}

export interface AllocationView {
  id: number;
  orderId: number;
  orderNo?: string | null;
  status: 'CONFIRMED' | 'CANCELLED';
  reason?: string | null;
  cancelledBy?: string | null;
  cancelReason?: string | null;
  version: number;
  lines: AllocationLineView[];
}

export function listBatches(params: {
  productId?: number;
  node?: string;
  seamState?: string;
  includeEmpty?: boolean;
} = {}): Promise<InventoryBatchView[]> {
  const query = new URLSearchParams();
  if (params.productId) query.set('productId', String(params.productId));
  if (params.node) query.set('node', params.node);
  if (params.seamState) query.set('seamState', params.seamState);
  if (params.includeEmpty) query.set('includeEmpty', 'true');
  return apiFetch<InventoryBatchView[]>(`/api/inventory/batches?${query.toString()}`);
}

export function listSummary(params: { productId?: number; includeEmpty?: boolean } = {}): Promise<InventorySummaryView[]> {
  const query = new URLSearchParams();
  if (params.productId) query.set('productId', String(params.productId));
  if (params.includeEmpty) query.set('includeEmpty', 'true');
  return apiFetch<InventorySummaryView[]>(`/api/inventory/summary?${query.toString()}`);
}

export function listMovements(params: { movementType?: string; batchId?: number } = {}): Promise<MovementView[]> {
  const query = new URLSearchParams();
  if (params.movementType) query.set('movementType', params.movementType);
  if (params.batchId) query.set('batchId', String(params.batchId));
  return apiFetch<MovementView[]>(`/api/inventory/movements?${query.toString()}`);
}

export function createOpening(body: {
  productId: number;
  node: InventoryNode;
  seamState: SeamState;
  quantity: number;
  inventoryDate: string;
  note?: string;
}): Promise<InventoryBatchView> {
  return apiFetch<InventoryBatchView>('/api/inventory/batches', { method: 'POST', body: JSON.stringify(body) });
}

export function adjustInventory(body: {
  batchId: number;
  actualQuantity: number;
  reason: string;
  note?: string;
}): Promise<InventoryBatchView> {
  return apiFetch<InventoryBatchView>('/api/inventory/adjustments', { method: 'POST', body: JSON.stringify(body) });
}

export function recommendBatches(productId: number, targetNode: InventoryNode): Promise<RecommendationView[]> {
  const query = new URLSearchParams({ productId: String(productId), targetNode });
  return apiFetch<RecommendationView[]>(`/api/inventory/recommendations?${query.toString()}`);
}

export function allocateInventory(body: {
  orderId: number;
  reason?: string;
  lines: { batchId: number; orderItemId: number; quantity: number; targetNode: InventoryNode }[];
}): Promise<AllocationView> {
  return apiFetch<AllocationView>('/api/inventory-allocations', { method: 'POST', body: JSON.stringify(body) });
}

export function listAllocations(orderId: number): Promise<AllocationView[]> {
  return apiFetch<AllocationView[]>(`/api/inventory-allocations?orderId=${orderId}`);
}

export function cancelAllocation(id: number, reason: string): Promise<AllocationView> {
  return apiFetch<AllocationView>(`/api/inventory-allocations/${id}/cancel`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}

export function reverseMovement(id: number, reason: string): Promise<MovementView> {
  return apiFetch<MovementView>(`/api/inventory/movements/${id}/reverse`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}

export const NODE_LABELS: Record<InventoryNode, string> = {
  MAKING: '制作',
  PACKING_BAG: '捏毛装袋',
  SEAM_CUTTING: '缝边剪袋',
  SHIPPABLE: '可发货',
};

export const SEAM_STATE_LABELS: Record<SeamState, string> = {
  NONE: '未缝边',
  DONE: '已缝边',
};

export const MOVEMENT_TYPE_LABELS: Record<string, string> = {
  OPENING: '期初入库',
  ADJUSTMENT: '盘点调整',
  ALLOCATION: '订单领用',
  ALLOCATION_CANCEL: '领用取消',
  REVERSAL: '流水冲销',
};
