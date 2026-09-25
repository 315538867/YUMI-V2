import { apiFetch } from './client';

/**
 * 生产 API（阶段五后端契约）：数量为整数，页面不做数量算术；
 * 待安排/当前可执行/等待上游由服务端从事实派生，前端只展示。
 */
export type ProductionNode = 'MAKING' | 'PACKING_BAG' | 'SEAM_CUTTING';
export type PlanType =
  | 'NORMAL'
  | 'REWORK'
  | 'REMAKE'
  | 'OVERTIME'
  | 'AFTER_SALES_REWORK'
  | 'AFTER_SALES_REPLACEMENT';
export type PlanStatus = 'PENDING' | 'VERIFIED' | 'CANCELLED';

export interface ProductionPlanView {
  id: number;
  planNo: string;
  planType: PlanType;
  orderId: number;
  orderNo?: string | null;
  orderItemId: number;
  lineNo: number;
  productNo?: string | null;
  productName?: string | null;
  node: ProductionNode;
  planDate: string;
  employeeId: number;
  employeeName: string;
  quantity: number;
  status: PlanStatus;
  sourceType: string;
  sourceId: number;
  nodeDemand: number;
  nodePending: number;
  nodeVerified: number;
  nodeInflow: number;
  schedulableQuantity: number;
  executableQuantity: number;
  waitingUpstream: boolean;
  note?: string | null;
  version: number;
}

export interface VerificationFlowView {
  node: string;
  quantity: number;
}

export interface ProductionVerificationView {
  id: number;
  planId: number;
  planNo: string;
  orderItemId: number;
  node: ProductionNode;
  planQuantity: number;
  completedQuantity: number;
  qualifiedQuantity: number;
  reworkQuantity: number;
  scrapQuantity: number;
  incompleteQuantity: number;
  verifyNote?: string | null;
  verifiedBy: string;
  flows: VerificationFlowView[];
  incompleteReminderId?: number | null;
}

export interface ReworkSourceView {
  id: number;
  verificationId: number;
  orderId: number;
  orderItemId: number;
  foundNode: ProductionNode;
  targetNode: ProductionNode;
  totalQuantity: number;
  arrangedQuantity: number;
  balanceQuantity: number;
  roundNo: number;
  previousSourceId?: number | null;
  reason?: string | null;
  version: number;
}

export interface RemakeSourceView {
  id: number;
  verificationId: number;
  orderId: number;
  orderItemId: number;
  scrapNode: ProductionNode;
  startNode: ProductionNode;
  totalQuantity: number;
  arrangedQuantity: number;
  balanceQuantity: number;
  reason?: string | null;
  version: number;
}

export interface IncompleteReminderView {
  id: number;
  orderId: number;
  orderNo?: string | null;
  orderItemId: number;
  lineNo: number;
  productNo?: string | null;
  productName?: string | null;
  node: ProductionNode;
  planId: number;
  planNo?: string | null;
  quantity: number;
  status: 'OPEN' | 'HANDLED';
  handlingType?: string | null;
  handledQuantity?: number | null;
  reason?: string | null;
}

export interface OvertimeReminderView {
  id: number;
  reminderType: 'OVERTIME_PENDING_VERIFY' | 'PLAN_ADJUSTMENT';
  orderId: number;
  orderItemId: number;
  node: ProductionNode;
  overtimePlanId: number;
  overtimePlanNo?: string | null;
  futurePlanId?: number | null;
  futurePlanNo?: string | null;
  futurePlanDate?: string | null;
  quantity: number;
  status: 'OPEN' | 'HANDLED';
  handlingType?: string | null;
  reason?: string | null;
}

export interface OvertimeTaskView {
  planId: number;
  planNo: string;
  node: ProductionNode;
  planDate: string;
  orderItemId: number;
  employeeId: number;
  employeeName: string;
  quantity: number;
  status: PlanStatus;
  preemptions: {
    id: number;
    futurePlanId: number;
    futurePlanNo?: string | null;
    futurePlanDate?: string | null;
    preemptedQuantity: number;
    status: 'ACTIVE' | 'RELEASED';
  }[];
}

export interface OtherScheduleView {
  id: number;
  scheduleNo: string;
  scheduleDate: string;
  employeeId: number;
  employeeName: string;
  hours: number;
  minutes: number;
  totalMinutes: number;
  status: 'PENDING' | 'VERIFIED' | 'CANCELLED';
  note?: string | null;
  verifiedMinutes?: number | null;
  effectiveMinutes?: number | null;
  corrected: boolean;
  cancelReason?: string | null;
  version: number;
}

export function listPlans(params: {
  dateFrom?: string;
  dateTo?: string;
  employeeId?: number;
  node?: string;
  status?: string;
  orderId?: number;
  orderItemId?: number;
} = {}): Promise<ProductionPlanView[]> {
  const query = new URLSearchParams();
  if (params.dateFrom) query.set('dateFrom', params.dateFrom);
  if (params.dateTo) query.set('dateTo', params.dateTo);
  if (params.employeeId) query.set('employeeId', String(params.employeeId));
  if (params.node) query.set('node', params.node);
  if (params.status) query.set('status', params.status);
  if (params.orderId) query.set('orderId', String(params.orderId));
  if (params.orderItemId) query.set('orderItemId', String(params.orderItemId));
  return apiFetch<ProductionPlanView[]>(`/api/production-plans?${query.toString()}`);
}

export function getPlan(id: number | string): Promise<ProductionPlanView> {
  return apiFetch<ProductionPlanView>(`/api/production-plans/${id}`);
}

export function createPlan(body: {
  orderItemId: number;
  node: ProductionNode;
  planDate: string;
  employeeId: number;
  quantity: number;
  note?: string;
}): Promise<ProductionPlanView> {
  return apiFetch<ProductionPlanView>('/api/production-plans', {
    method: 'POST',
    body: JSON.stringify({ planType: 'NORMAL', ...body }),
  });
}

export function cancelPlan(id: number | string, reason: string): Promise<ProductionPlanView> {
  return apiFetch<ProductionPlanView>(`/api/production-plans/${id}/cancel`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}

export function verifyPlan(
  id: number | string,
  body: {
    completedQuantity: number;
    qualifiedQuantity: number;
    reworkQuantity: number;
    scrapQuantity: number;
    verifyNote?: string;
  },
): Promise<ProductionVerificationView> {
  return apiFetch<ProductionVerificationView>(`/api/production-plans/${id}/verify`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function listReworkSources(orderItemId: number): Promise<ReworkSourceView[]> {
  return apiFetch<ReworkSourceView[]>(`/api/rework-sources?orderItemId=${orderItemId}`);
}

export function createReworkSource(body: {
  verificationId: number;
  targetNode: ProductionNode;
  quantity: number;
  reason?: string;
}): Promise<ReworkSourceView> {
  return apiFetch<ReworkSourceView>('/api/rework-sources', { method: 'POST', body: JSON.stringify(body) });
}

export function createReworkPlan(
  sourceId: number | string,
  body: { planDate: string; employeeId: number; quantity: number; note?: string },
): Promise<ProductionPlanView> {
  return apiFetch<ProductionPlanView>(`/api/rework-sources/${sourceId}/plans`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function listRemakeSources(orderItemId: number): Promise<RemakeSourceView[]> {
  return apiFetch<RemakeSourceView[]>(`/api/remake-sources?orderItemId=${orderItemId}`);
}

export function createRemakeSource(body: {
  verificationId: number;
  startNode: ProductionNode;
  quantity: number;
  reason?: string;
}): Promise<RemakeSourceView> {
  return apiFetch<RemakeSourceView>('/api/remake-sources', { method: 'POST', body: JSON.stringify(body) });
}

export function createRemakePlan(
  sourceId: number | string,
  body: { planDate: string; employeeId: number; quantity: number; note?: string },
): Promise<ProductionPlanView> {
  return apiFetch<ProductionPlanView>(`/api/remake-sources/${sourceId}/plans`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function listIncompleteReminders(): Promise<IncompleteReminderView[]> {
  return apiFetch<IncompleteReminderView[]>('/api/production-reminders/incomplete');
}

export function rescheduleReminder(
  id: number | string,
  body: { planDate: string; employeeId: number; quantity: number; note?: string },
): Promise<IncompleteReminderView> {
  return apiFetch<IncompleteReminderView>(`/api/production-reminders/incomplete/${id}/reschedule`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function deferReminder(id: number | string, reason: string): Promise<IncompleteReminderView> {
  return apiFetch<IncompleteReminderView>(`/api/production-reminders/incomplete/${id}/defer`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}

export function listOvertimeReminders(): Promise<OvertimeReminderView[]> {
  return apiFetch<OvertimeReminderView[]>('/api/production-reminders/overtime');
}

export function adjustOvertimePlan(
  id: number | string,
  body: { newQuantity: number; reason: string },
): Promise<OvertimeReminderView> {
  return apiFetch<OvertimeReminderView>(`/api/production-reminders/overtime/${id}/adjust-plan`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function noOvertimeAdjustment(id: number | string, reason: string): Promise<OvertimeReminderView> {
  return apiFetch<OvertimeReminderView>(`/api/production-reminders/overtime/${id}/no-adjustment`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}

export function createOvertimeTask(body: {
  orderItemId: number;
  node: ProductionNode;
  planDate: string;
  employeeId: number;
  lines: { futurePlanId: number; quantity: number }[];
  note?: string;
}): Promise<OvertimeTaskView> {
  return apiFetch<OvertimeTaskView>('/api/overtime-tasks', { method: 'POST', body: JSON.stringify(body) });
}

export function verifyOvertimeTask(
  id: number | string,
  body: {
    completedQuantity: number;
    qualifiedQuantity: number;
    reworkQuantity: number;
    scrapQuantity: number;
    verifyNote?: string;
  },
): Promise<ProductionVerificationView> {
  return apiFetch<ProductionVerificationView>(`/api/overtime-tasks/${id}/verify`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function listOtherSchedules(params: {
  dateFrom?: string;
  dateTo?: string;
  employeeId?: number;
  status?: string;
} = {}): Promise<OtherScheduleView[]> {
  const query = new URLSearchParams();
  if (params.dateFrom) query.set('dateFrom', params.dateFrom);
  if (params.dateTo) query.set('dateTo', params.dateTo);
  if (params.employeeId) query.set('employeeId', String(params.employeeId));
  if (params.status) query.set('status', params.status);
  return apiFetch<OtherScheduleView[]>(`/api/other-schedules?${query.toString()}`);
}

export function createOtherSchedule(body: {
  scheduleDate: string;
  employeeId: number;
  hours: number;
  minutes: number;
  note?: string;
}): Promise<OtherScheduleView> {
  return apiFetch<OtherScheduleView>('/api/other-schedules', { method: 'POST', body: JSON.stringify(body) });
}

export function verifyOtherSchedule(
  id: number | string,
  body: { hours: number; minutes: number; note?: string },
): Promise<OtherScheduleView> {
  return apiFetch<OtherScheduleView>(`/api/other-schedules/${id}/verify`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function cancelOtherSchedule(id: number | string, reason: string): Promise<OtherScheduleView> {
  return apiFetch<OtherScheduleView>(`/api/other-schedules/${id}/cancel`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}

export function correctOtherSchedule(
  id: number | string,
  body: { hours: number; minutes: number; reason: string },
): Promise<OtherScheduleView> {
  return apiFetch<OtherScheduleView>(`/api/other-schedules/${id}/corrections`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export const PLAN_TYPE_LABELS: Record<PlanType, string> = {
  NORMAL: '正常',
  REWORK: '返工',
  REMAKE: '重做',
  OVERTIME: '超额',
  AFTER_SALES_REWORK: '售后返工',
  AFTER_SALES_REPLACEMENT: '售后补发',
};

export const PLAN_STATUS_LABELS: Record<PlanStatus, string> = {
  PENDING: '待执行',
  VERIFIED: '已核验',
  CANCELLED: '已取消',
};

export const PLAN_STATUS_COLORS: Record<PlanStatus, string> = {
  PENDING: 'blue',
  VERIFIED: 'green',
  CANCELLED: 'default',
};

export const REWORK_ROUND_LABELS = (roundNo: number): string => `第 ${roundNo} 次返工`;
