import { apiFetch } from './client';

/**
 * 生产 API（阶段五后端契约，施工文档 §9）：任务头 + 明细是唯一写模型；
 * 计划数量、实际流入、当前可执行、回转余额、任务状态全部由服务端从事实派生，前端只展示、不做权威算术。
 * 所有写命令由 `apiFetch` 自动携带 `Idempotency-Key`，页面不手工拼装。
 */

export type ProductionNode = 'MAKING' | 'PACKING_BAG' | 'SEAM_CUTTING';
/** 任务头类型只允许 NORMAL / REWORK；超额预占与其他排班是独立资源。 */
export type ProductionTaskType = 'NORMAL' | 'REWORK';
export type TaskItemStatus = 'PENDING' | 'VERIFIED' | 'CANCELLED';
export type DerivedTaskStatus =
  | 'SCHEDULED'
  | 'IN_PROGRESS'
  | 'PENDING_VERIFY'
  | 'PARTIALLY_VERIFIED'
  | 'VERIFIED'
  | 'CANCELLED';
export type ProductionSourceType = 'ORDER' | 'REWORK_SOURCE' | 'QUANTITY_RETURN' | 'AFTER_SALES_SOURCE';

/** 明细核验事实（阶段五 5.18）：逐明细合格/返工/报废/未完成与核验人时间，未核验时为 null。 */
export interface ItemVerificationView {
  plannedQuantity: number;
  completedQuantity: number;
  qualifiedQuantity: number;
  reworkQuantity: number;
  scrapQuantity: number;
  incompleteQuantity: number;
  note?: string | null;
  verifiedBy?: string | null;
  verifiedAt?: string | null;
}

/** 明细视图：计划/实际流入/当前可执行/回转余额分栏返回。 */
export interface ProductionTaskItemView {
  id: number;
  itemNo: number;
  orderId: number;
  orderNo?: string | null;
  orderItemId: number;
  lineNo: number;
  productId: number;
  productNo: string;
  productName: string;
  node: ProductionNode;
  plannedQuantity: number;
  sourceType: ProductionSourceType | string;
  sourceId: number;
  standardMinutes?: number | null;
  estimatedMinutes?: number | null;
  normalMinutes?: number | null;
  reworkMinutes?: number | null;
  capacityNotice?: string | null;
  dailyMaxCapacity?: number | null;
  usedNormalQuantity?: number | null;
  remainingNormalQuantity?: number | null;
  actualInflow: number;
  verifiedProcessedQuantity: number;
  executableQuantity: number;
  waitingUpstream: boolean;
  status: TaskItemStatus;
  cancelledBy?: string | null;
  cancelReason?: string | null;
  /** 服务端返回的逐明细核验分解，未核验时为 null。 */
  verification?: ItemVerificationView | null;
  version: number;
}

/** 任务头视图：`derivedStatus` 完全派生，不提供人工状态覆盖。 */
export interface ProductionTaskView {
  id: number;
  taskNo: string;
  taskDate: string;
  employeeId: number;
  employeeName: string;
  workTypeId: number;
  workTypeName: string;
  taskType: ProductionTaskType;
  note?: string | null;
  derivedStatus: DerivedTaskStatus;
  items: ProductionTaskItemView[];
  version: number;
}

export interface ProductionTaskListParams {
  dateFrom?: string;
  dateTo?: string;
  employeeId?: number;
  workTypeId?: number;
  taskType?: string;
  status?: string;
  orderId?: number;
  productId?: number;
}

export interface ProductionTaskCreateItemRequest {
  orderItemId: number;
  plannedQuantity: number;
  sourceType: ProductionSourceType | string;
  sourceId?: number;
}

export interface ProductionTaskCreateRequest {
  taskDate: string;
  employeeId: number;
  workTypeId: number;
  taskType: ProductionTaskType;
  note?: string;
  items: ProductionTaskCreateItemRequest[];
}

export interface VerificationFlowView {
  node: string;
  quantity: number;
}

export interface VerificationItemResultView {
  taskItemId: number;
  plannedQuantity: number;
  completedQuantity: number;
  qualifiedQuantity: number;
  reworkQuantity: number;
  scrapQuantity: number;
  incompleteQuantity: number;
  flows: VerificationFlowView[];
  incompleteReminderId?: number | null;
}

export interface ProductionVerificationView {
  taskId: number;
  taskNo: string;
  derivedStatus: DerivedTaskStatus;
  items: VerificationItemResultView[];
}

export interface VerifyItemRequest {
  taskItemId: number;
  qualifiedQuantity: number;
  reworkQuantity: number;
  scrapQuantity: number;
  note?: string;
}

export interface ReworkSourceView {
  id: number;
  sourceNo: string;
  originVerificationId: number;
  originTaskItemId: number;
  orderId: number;
  orderItemId: number;
  productId: number;
  node: ProductionNode;
  totalQuantity: number;
  arrangedQuantity: number;
  availableQuantity: number;
  roundNo: number;
  previousSourceId?: number | null;
  reason?: string | null;
}

export interface ProductionScrapView {
  id: number;
  verificationId: number;
  taskItemId: number;
  orderId: number;
  orderItemId: number;
  productId: number;
  node: ProductionNode;
  scrapQuantity: number;
  reason?: string | null;
  operatorUsername: string;
  recordedAt: string;
}

export interface ProductionQuantityReturnView {
  id: number;
  scrapRecordId: number;
  orderId: number;
  orderItemId: number;
  productId: number;
  node: ProductionNode;
  returnedQuantity: number;
  allocatedQuantity: number;
  availableQuantity: number;
}

export interface IncompleteReminderView {
  id: number;
  reminderType: string;
  orderId: number;
  orderItemId: number;
  node: ProductionNode;
  taskItemId?: number | null;
  verificationId?: number | null;
  quantity: number;
  status: 'OPEN' | 'HANDLED';
  handlingType?: string | null;
  handledQuantity?: number | null;
  reason?: string | null;
  handledBy?: string | null;
  handledAt?: string | null;
}

export interface OvertimeItemView {
  id: number;
  itemNo: number;
  orderId: number;
  orderItemId: number;
  productId: number;
  productNo: string;
  productName: string;
  node: ProductionNode;
  plannedQuantity: number;
  status: TaskItemStatus;
  completedQuantity?: number | null;
  qualifiedQuantity?: number | null;
  reworkQuantity?: number | null;
  scrapQuantity?: number | null;
  incompleteQuantity?: number | null;
  verifyNote?: string | null;
  verifiedBy?: string | null;
  verifiedAt?: string | null;
  version: number;
}

export interface OvertimePreemptionView {
  id: number;
  overtimeItemId: number;
  futureTaskItemId: number;
  orderId: number;
  orderItemId: number;
  node: ProductionNode;
  preemptedQuantity: number;
  status: string;
  releasedAt?: string | null;
  releasedBy?: string | null;
  releaseReason?: string | null;
}

export interface OvertimeReminderView {
  id: number;
  reminderType: string;
  orderId: number;
  orderItemId: number;
  node: ProductionNode;
  preemptionId?: number | null;
  futureTaskItemId?: number | null;
  quantity: number;
  status: 'OPEN' | 'HANDLED';
  handlingType?: string | null;
  reason?: string | null;
}

export interface OvertimeTaskView {
  id: number;
  taskNo: string;
  taskDate: string;
  employeeId: number;
  employeeName: string;
  workType: ProductionNode;
  workTypeName: string;
  note?: string | null;
  version: number;
  items: OvertimeItemView[];
  preemptions: OvertimePreemptionView[];
  reminders: OvertimeReminderView[];
}

export interface CreateOvertimeTaskRequest {
  taskDate: string;
  employeeId: number;
  note?: string;
  items: { futureTaskItemId: number; plannedQuantity: number }[];
}

export interface VerifyOvertimeItemRequest {
  overtimeItemId: number;
  completedQuantity: number;
  qualifiedQuantity: number;
  reworkQuantity: number;
  scrapQuantity: number;
  note?: string;
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

/* ---------------- 生产任务 ---------------- */

function taskQuery(params: ProductionTaskListParams): string {
  const query = new URLSearchParams();
  if (params.dateFrom) query.set('dateFrom', params.dateFrom);
  if (params.dateTo) query.set('dateTo', params.dateTo);
  if (params.employeeId) query.set('employeeId', String(params.employeeId));
  if (params.workTypeId) query.set('workTypeId', String(params.workTypeId));
  if (params.taskType) query.set('taskType', params.taskType);
  if (params.status) query.set('status', params.status);
  if (params.orderId) query.set('orderId', String(params.orderId));
  if (params.productId) query.set('productId', String(params.productId));
  return query.toString();
}

export function listProductionTasks(params: ProductionTaskListParams = {}): Promise<ProductionTaskView[]> {
  return apiFetch<ProductionTaskView[]>(`/api/production-tasks?${taskQuery(params)}`);
}

export function getProductionTask(id: number | string): Promise<ProductionTaskView> {
  return apiFetch<ProductionTaskView>(`/api/production-tasks/${id}`);
}

/**
 * 任务事实时间线（阶段五 5.18）：单一只读端点返回任务的全部不可变事实，
 * 服务端已按 `factTime ASC, factType ASC, factId ASC` 排序；前端不再从多个资源读端点拼装。
 */
export function getProductionTaskFacts(id: number | string): Promise<ProductionFact[]> {
  return apiFetch<ProductionFact[]>(`/api/production-tasks/${id}/facts`);
}

export function createProductionTask(body: ProductionTaskCreateRequest): Promise<ProductionTaskView> {
  return apiFetch<ProductionTaskView>('/api/production-tasks', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function cancelProductionTaskItem(
  taskId: number | string,
  itemId: number | string,
  reason: string,
): Promise<ProductionTaskView> {
  return apiFetch<ProductionTaskView>(`/api/production-tasks/${taskId}/items/${itemId}/cancel`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}

export function verifyProductionTask(
  taskId: number | string,
  items: VerifyItemRequest[],
): Promise<ProductionVerificationView> {
  return apiFetch<ProductionVerificationView>(`/api/production-tasks/${taskId}/verify`, {
    method: 'POST',
    body: JSON.stringify({ items }),
  });
}

/* ---------------- 返工来源 ---------------- */

export function listReworkSources(
  params: { orderItemId?: number; node?: string; status?: string } = {},
): Promise<ReworkSourceView[]> {
  const query = new URLSearchParams();
  if (params.orderItemId) query.set('orderItemId', String(params.orderItemId));
  if (params.node) query.set('node', params.node);
  if (params.status) query.set('status', params.status);
  return apiFetch<ReworkSourceView[]>(`/api/rework-sources?${query.toString()}`);
}

export function getReworkSource(id: number | string): Promise<ReworkSourceView> {
  return apiFetch<ReworkSourceView>(`/api/rework-sources/${id}`);
}

export function createReworkSource(body: {
  originVerificationId: number;
  quantity: number;
  reason?: string;
  previousSourceId?: number;
}): Promise<ReworkSourceView> {
  return apiFetch<ReworkSourceView>('/api/rework-sources', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function createReworkTaskFromSource(
  sourceId: number | string,
  body: {
    taskDate: string;
    employeeId: number;
    note?: string;
    items: { plannedQuantity: number }[];
  },
): Promise<ProductionTaskView> {
  return apiFetch<ProductionTaskView>(`/api/rework-sources/${sourceId}/tasks`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/* ---------------- 报废 / 回转 / 未完成提醒 ---------------- */

export function listProductionScraps(
  params: { orderItemId?: number; node?: string } = {},
): Promise<ProductionScrapView[]> {
  const query = new URLSearchParams();
  if (params.orderItemId) query.set('orderItemId', String(params.orderItemId));
  if (params.node) query.set('node', params.node);
  return apiFetch<ProductionScrapView[]>(`/api/production-scraps?${query.toString()}`);
}

export function getProductionScrap(id: number | string): Promise<ProductionScrapView> {
  return apiFetch<ProductionScrapView>(`/api/production-scraps/${id}`);
}

export function listProductionQuantityReturns(
  params: { orderItemId?: number; node?: string } = {},
): Promise<ProductionQuantityReturnView[]> {
  const query = new URLSearchParams();
  if (params.orderItemId) query.set('orderItemId', String(params.orderItemId));
  if (params.node) query.set('node', params.node);
  return apiFetch<ProductionQuantityReturnView[]>(`/api/production-quantity-returns?${query.toString()}`);
}

export function listIncompleteReminders(
  params: { orderItemId?: number; node?: string } = {},
): Promise<IncompleteReminderView[]> {
  const query = new URLSearchParams();
  if (params.orderItemId) query.set('orderItemId', String(params.orderItemId));
  if (params.node) query.set('node', params.node);
  return apiFetch<IncompleteReminderView[]>(`/api/production-reminders/incomplete?${query.toString()}`);
}

export function deferIncompleteReminder(id: number | string, reason: string): Promise<IncompleteReminderView> {
  return apiFetch<IncompleteReminderView>(`/api/production-reminders/${id}/defer`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}

/* ---------------- 超额任务 ---------------- */

export function createOvertimeTask(body: CreateOvertimeTaskRequest): Promise<OvertimeTaskView> {
  return apiFetch<OvertimeTaskView>('/api/overtime-tasks', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function getOvertimeTask(id: number | string): Promise<OvertimeTaskView> {
  return apiFetch<OvertimeTaskView>(`/api/overtime-tasks/${id}`);
}

/**
 * 按执行日期列出超额任务（工作台提醒读取，任务 5.14/5.17）：
 * 供工作台在周历旁展示当周每日的超额预占与提醒，不新增顶级导航。
 */
export function listOvertimeTasks(taskDate: string): Promise<OvertimeTaskView[]> {
  const query = new URLSearchParams();
  if (taskDate) query.set('taskDate', taskDate);
  return apiFetch<OvertimeTaskView[]>(`/api/overtime-tasks?${query.toString()}`);
}

export function verifyOvertimeTask(
  id: number | string,
  items: VerifyOvertimeItemRequest[],
): Promise<OvertimeTaskView> {
  return apiFetch<OvertimeTaskView>(`/api/overtime-tasks/${id}/verify`, {
    method: 'POST',
    body: JSON.stringify({ items }),
  });
}

/* ---------------- 其他排班（沿用既有契约，不改造） ---------------- */

export function listOtherSchedules(
  params: { dateFrom?: string; dateTo?: string; employeeId?: number; status?: string } = {},
): Promise<OtherScheduleView[]> {
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

/* ---------------- 展示标签 ---------------- */

export const TASK_TYPE_LABELS: Record<ProductionTaskType, string> = {
  NORMAL: '正常生产',
  REWORK: '返工（工序内部）',
};

export const ITEM_STATUS_LABELS: Record<TaskItemStatus, string> = {
  PENDING: '待执行',
  VERIFIED: '已核验',
  CANCELLED: '已取消',
};

export const DERIVED_STATUS_LABELS: Record<DerivedTaskStatus, string> = {
  SCHEDULED: '已排班',
  IN_PROGRESS: '进行中',
  PENDING_VERIFY: '待核验',
  PARTIALLY_VERIFIED: '部分核验',
  VERIFIED: '已核验',
  CANCELLED: '已取消',
};

export const DERIVED_STATUS_COLORS: Record<DerivedTaskStatus, string> = {
  SCHEDULED: 'blue',
  IN_PROGRESS: 'geekblue',
  PENDING_VERIFY: 'gold',
  PARTIALLY_VERIFIED: 'orange',
  VERIFIED: 'green',
  CANCELLED: 'default',
};

export const SOURCE_TYPE_LABELS: Record<string, string> = {
  ORDER: '订单需求',
  REWORK_SOURCE: '返工来源',
  QUANTITY_RETURN: '报废回转',
  AFTER_SALES_SOURCE: '售后来源',
};

export const OTHER_SCHEDULE_STATUS_LABELS: Record<OtherScheduleView['status'], string> = {
  PENDING: '待执行',
  VERIFIED: '已核验',
  CANCELLED: '已取消',
};

/** 事实时间线事件类型（阶段五 5.18）：与后端 `FactView.factType` 一一对应的 13 类事件。 */
export type ProductionFactType =
  | 'PLAN'
  | 'VERIFICATION'
  | 'REWORK_FACT'
  | 'REWORK_SOURCE'
  | 'SCRAP'
  | 'QUANTITY_RETURN'
  | 'INVENTORY_INFLOW'
  | 'QUALIFIED_FLOW'
  | 'INCOMPLETE'
  | 'CANCEL'
  | 'OVERTIME_PREEMPTION'
  | 'REMINDER'
  | 'TIME_CORRECTION';

/**
 * 事实时间线事件（阶段五 5.18，`GET /api/production-tasks/{id}/facts`）。
 * 服务端已按 `factTime ASC, factType ASC, factId ASC` 排序。
 * 语义：`VERIFICATION.note` 携带「合格/返工/报废/未完成」分解；
 * `REWORK_FACT.referenceId` 为父核验 id；`REWORK_SOURCE.referenceId` 为父来源 id；
 * `QUALIFIED_FLOW.node` 为目标工序；`SCRAP.reason` / `CANCEL.reason` 为原因；`operator` 为经办管理员。
 */
export interface ProductionFact {
  /** 事实 id：同 `factTime` 时按 `factId ASC` 三级排序。 */
  factId: number;
  factTime: string | null;
  factType: ProductionFactType;
  /** 工序；`QUALIFIED_FLOW` 为目标工序。 */
  node?: string | null;
  quantity?: number | null;
  orderItemId?: number | null;
  /** 参考 id：核验/返工事实为核验 id，返工来源为父来源 id，其余为各自来源 id。 */
  referenceId?: number | null;
  /** 经办管理员。 */
  operator?: string | null;
  reason?: string | null;
  /** 各事件的补充文本（核验分解、轮次/已安排、状态等）。 */
  note?: string | null;
}

export const FACT_TYPE_LABELS: Record<ProductionFactType, string> = {
  PLAN: '计划',
  VERIFICATION: '核验',
  REWORK_FACT: '返工事实',
  REWORK_SOURCE: '返工来源',
  SCRAP: '报废',
  QUANTITY_RETURN: '数量回转',
  INVENTORY_INFLOW: '库存接入',
  QUALIFIED_FLOW: '合格流转',
  INCOMPLETE: '未完成',
  CANCEL: '取消',
  OVERTIME_PREEMPTION: '超额预占',
  REMINDER: '提醒',
  TIME_CORRECTION: '工时更正',
};

export const REWORK_ROUND_LABELS = (roundNo: number): string => `第 ${roundNo} 次返工`;
