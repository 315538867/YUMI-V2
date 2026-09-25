import { apiFetch } from './client';

/**
 * 收退款与结清 API（阶段七后端契约）：金额为字符串；收款与退款不可修改删除；
 * 收款状态与关闭条件由服务端从事实派生。
 */
export interface PaymentView {
  id: number;
  paymentNo: string;
  amount: string;
  businessDate: string;
  method: string;
  note?: string | null;
  operatorUsername?: string | null;
}

export interface RefundView {
  id: number;
  refundNo: string;
  amount: string;
  businessDate: string;
  method: string;
  reason: string;
  note?: string | null;
  sourceType: 'ORDER_CHANGE' | 'AFTER_SALES';
  sourceId: number;
  operatorUsername?: string | null;
}

export interface CloseConditionView {
  name: string;
  satisfied: boolean;
  detail: string;
}

export interface SettlementView {
  orderId: number;
  orderNo: string;
  orderStatus: string;
  receivableStatus: string;
  paidAmount: string;
  changeRefundAmount: string;
  afterSalesRefundAmount: string;
  netSettledAmount: string;
  actualNetReceived: string;
  effectiveReceivableAmount: string;
  refundPendingAmount: string;
  payments: PaymentView[];
  refunds: RefundView[];
  closeConditions: CloseConditionView[];
}

export function getSettlement(orderId: number | string): Promise<SettlementView> {
  return apiFetch<SettlementView>(`/api/orders/${orderId}/settlement`);
}

export function registerPayment(
  orderId: number | string,
  body: { amount: string; businessDate: string; method: string; note?: string },
): Promise<SettlementView> {
  return apiFetch<SettlementView>(`/api/orders/${orderId}/payments`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function registerRefund(
  orderId: number | string,
  body: {
    amount: string;
    businessDate: string;
    method: string;
    reason: string;
    note?: string;
    sourceType: 'ORDER_CHANGE' | 'AFTER_SALES';
    sourceId: number;
  },
): Promise<SettlementView> {
  return apiFetch<SettlementView>(`/api/orders/${orderId}/refunds`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function closeOrder(orderId: number | string): Promise<SettlementView> {
  return apiFetch<SettlementView>(`/api/orders/${orderId}/close`, { method: 'POST' });
}

export const RECEIVABLE_STATUS_COLORS: Record<string, string> = {
  未收款: 'default',
  部分收款: 'blue',
  已结清: 'green',
  待退款: 'orange',
  已取消: 'default',
  已关闭: 'default',
};

export const REFUND_SOURCE_LABELS: Record<string, string> = {
  ORDER_CHANGE: '订单变更退款',
  AFTER_SALES: '售后退款',
};
