import dayjs from 'dayjs';
import {
  FACT_TYPE_LABELS,
  SOURCE_TYPE_LABELS,
  type IncompleteReminderView,
  type ProductionFact,
  type ProductionTaskItemView,
  type ProductionTaskView,
  type VerifyItemRequest,
} from '../../api/production';
import { NODE_LABELS } from '../../api/inventory';
import type { ApiFieldError } from '../../api/errors';
import { YumiApiError } from '../../api/errors';

/**
 * 生产页面纯逻辑（阶段五 5.17/5.18）：
 * 只做展示分组、排序、字段错误定位与「只读/写」请求分类，**不做任何权威数量计算**。
 * 计划数量、实际流入、当前可执行、回转余额一律取服务端返回值。
 */

/**
 * 事实时间展示为秒级：服务端返回微秒精度 ISO 串，界面上只到秒。
 * 时间线、明细核验时间等所有事实时间统一走这里，避免把原始微秒串直接显示给用户。
 */
export function formatFactTime(value: string | null | undefined): string {
  if (!value) {
    return '—';
  }
  const normalized = value.replace('T', ' ');
  const dot = normalized.indexOf('.');
  return dot > 0 ? normalized.slice(0, dot) : normalized.slice(0, 19);
}

export const WEEKDAY_LABELS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'] as const;

export interface WeekDay {
  date: string;
  weekday: string;
  label: string;
}

/** 周一为一周起点，返回横向周历的 7 个日期列。 */
export function weekDays(anchor: string): WeekDay[] {
  const base = dayjs(anchor);
  const dow = base.day();
  const diff = dow === 0 ? -6 : 1 - dow;
  const monday = base.add(diff, 'day').startOf('day');
  return WEEKDAY_LABELS.map((weekday, index) => {
    const day = monday.add(index, 'day');
    return { date: day.format('YYYY-MM-DD'), weekday, label: day.format('MM-DD') };
  });
}

/** 周区间（周一到周日），供列表查询与周切换使用。 */
export function weekRange(anchor: string): [string, string] {
  const days = weekDays(anchor);
  return [days[0].date, days[6].date];
}

/** 周切换：只改变查询区间，不产生任何写请求。 */
export function shiftWeek(anchor: string, deltaWeeks: number): string {
  return dayjs(anchor).add(deltaWeeks * 7, 'day').format('YYYY-MM-DD');
}

export function todayString(): string {
  return dayjs().format('YYYY-MM-DD');
}

/**
 * 固定员工调色板（5 色，与原型 index.html 一致）：同一员工在任意页面得到同一颜色，
 * 仅作属性标记，不构成导航分组。颜色按员工 id 确定性分配。
 */
export type EmployeeColorKey = 'blue' | 'purple' | 'green' | 'orange' | 'slate';

const EMPLOYEE_COLOR_KEYS: readonly EmployeeColorKey[] = ['blue', 'purple', 'green', 'orange', 'slate'];

const EMPLOYEE_COLOR_HEX: Record<EmployeeColorKey, string> = {
  blue: '#1677ff',
  purple: '#722ed1',
  green: '#389e0d',
  orange: '#d46b08',
  slate: '#595959',
};

const EMPLOYEE_BG_HEX: Record<EmployeeColorKey, string> = {
  blue: '#f0f7ff',
  purple: '#f9f0ff',
  green: '#f6ffed',
  orange: '#fff7e6',
  slate: '#fafafa',
};

export function employeeColorKey(employeeId: number): EmployeeColorKey {
  return EMPLOYEE_COLOR_KEYS[Math.abs(Math.trunc(employeeId)) % EMPLOYEE_COLOR_KEYS.length];
}

/** 员工色 CSS 类名（`employee-blue` 等），用于 week-card / employee-heading 的变量作用域。 */
export function employeeColorClass(employeeId: number): string {
  return `employee-${employeeColorKey(employeeId)}`;
}

/** 员工主色（Badge 点、卡片左边框用色）。 */
export function employeeBadgeColor(employeeId: number): string {
  return EMPLOYEE_COLOR_HEX[employeeColorKey(employeeId)];
}

/** 员工浅色底（当前日/员工列背景）。 */
export function employeeBackgroundColor(employeeId: number): string {
  return EMPLOYEE_BG_HEX[employeeColorKey(employeeId)];
}

export interface EmployeeTaskGroup {
  employeeId: number;
  employeeName: string;
  tasks: ProductionTaskView[];
}

/** 今日列表按员工分组（员工是属性分组，不是导航）：按员工 id 升序，稳定可断言。 */
export function groupTasksByEmployee(tasks: ProductionTaskView[]): EmployeeTaskGroup[] {
  const groups = new Map<number, EmployeeTaskGroup>();
  for (const task of tasks) {
    const group = groups.get(task.employeeId) ?? {
      employeeId: task.employeeId,
      employeeName: task.employeeName,
      tasks: [],
    };
    group.tasks.push(task);
    groups.set(task.employeeId, group);
  }
  return [...groups.values()].sort((left, right) => left.employeeId - right.employeeId);
}

/** 任务是否已完成（用于 today-row / week-card 的 `.is-done` / `.done` 弱化）。 */
export function isTaskDone(task: ProductionTaskView): boolean {
  return task.derivedStatus === 'VERIFIED' || task.derivedStatus === 'CANCELLED';
}

/** 任务是否处于异常态（未完成提醒 / 等待上游 / 容量提示），用于 `.issue` 卡片样式。 */
export function taskHasIssue(
  task: ProductionTaskView,
  remindersByItemNode?: Map<string, IncompleteReminderView[]>,
): boolean {
  return task.items.some(item => {
    if (item.waitingUpstream || item.capacityNotice) {
      return true;
    }
    const reminders = remindersByItemNode?.get(`${item.orderItemId}:${item.node}`) ?? [];
    return reminders.some(reminder => reminder.status === 'OPEN');
  });
}

/**
 * 事实时间线防御性排序：`factTime ASC, factType ASC, factId ASC`。
 * 服务端 `/facts` 已排序，这里仅作二次兜底；返回新数组，不改动入参，`factTime` 为空视为最早。
 */
export function sortFacts(facts: ProductionFact[]): ProductionFact[] {
  return [...facts].sort((left, right) => {
    const leftTime = left.factTime ?? '';
    const rightTime = right.factTime ?? '';
    if (leftTime !== rightTime) {
      return leftTime < rightTime ? -1 : 1;
    }
    if (left.factType !== right.factType) {
      return left.factType < right.factType ? -1 : 1;
    }
    return left.factId - right.factId;
  });
}

/** 事实事件的可读摘要：优先使用服务端 `note`，再补充事件类型与工序。 */
export function factSummary(fact: ProductionFact): string {
  const label = FACT_TYPE_LABELS[fact.factType] ?? fact.factType;
  const nodeText = fact.node ? `（${NODE_LABELS[fact.node as keyof typeof NODE_LABELS] ?? fact.node}）` : '';
  // PLAN 的 note 是来源类型；其余事件 note 里可能带状态词（OPEN/ACTIVE…），都要转中文再展示
  const note = fact.note ? displayTokens(fact.note) : '';
  switch (fact.factType) {
    case 'PLAN':
      return `计划${nodeText}${note ? ` · ${note}` : ''}`;
    case 'VERIFICATION':
    case 'REWORK_FACT':
    case 'INCOMPLETE':
    case 'CANCEL':
    case 'OVERTIME_PREEMPTION':
    case 'REMINDER':
    case 'TIME_CORRECTION':
      return note ? `${label} · ${note}` : label;
    default:
      return `${label}${nodeText}${note ? ` · ${note}` : ''}`;
  }
}

/** 事实 note 内部的机器词（来源类型、状态）转中文，避免把英文枚举显示给用户。 */
const NOTE_TOKEN_LABELS: Record<string, string> = {
  ...SOURCE_TYPE_LABELS,
  OPEN: '待处理',
  CLOSED: '已处理',
  ACTIVE: '有效',
  RELEASED: '已释放',
};

function displayTokens(value: string): string {
  return value
    .split(/(\s+)/)
    .map(part => NOTE_TOKEN_LABELS[part] ?? part)
    .join('');
}

/** 事实参考列文案：返工事实指向父核验，返工来源指向父来源，其余为来源 id。 */
export function factReferenceLabel(fact: ProductionFact): string | null {
  if (fact.referenceId == null) {
    return null;
  }
  if (fact.factType === 'REWORK_FACT' || fact.factType === 'VERIFICATION') {
    return `父核验 #${fact.referenceId}`;
  }
  if (fact.factType === 'REWORK_SOURCE') {
    return `父来源 #${fact.referenceId}`;
  }
  return `来源 #${fact.referenceId}`;
}

/** 按 `orderItemId:node` 归组未完成提醒，附着到受影响的明细上。 */
export function remindersByItemNode(
  reminders: IncompleteReminderView[],
): Map<string, IncompleteReminderView[]> {
  const map = new Map<string, IncompleteReminderView[]>();
  for (const reminder of reminders) {
    const key = `${reminder.orderItemId}:${reminder.node}`;
    const list = map.get(key) ?? [];
    list.push(reminder);
    map.set(key, list);
  }
  return map;
}

export interface ItemFieldError {
  itemIndex: number;
  field: string;
  message: string;
}

/**
 * 把服务端 `fieldErrors`（形如 `items[1].qualifiedQuantity`）定位到具体明细，
 * 供核验页逐条展示；无法定位的错误归入 `itemIndex = -1`。
 */
export function mapFieldErrorsToItems(fieldErrors: ApiFieldError[]): ItemFieldError[] {
  return fieldErrors.map(entry => {
    const match = /^items\[(\d+)\]\.?(.*)$/.exec(entry.field);
    if (!match) {
      return { itemIndex: -1, field: entry.field, message: entry.message };
    }
    return { itemIndex: Number(match[1]), field: match[2] || 'items', message: entry.message };
  });
}

export interface VerifyInput {
  qualifiedQuantity?: number | null;
  reworkQuantity?: number | null;
  scrapQuantity?: number | null;
}

function num(value: number | null | undefined): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

/** 本次完成 = 合格 + 返工 + 报废（仅用于等式提示，未完成由服务端计算）。 */
export function completedQuantity(input: VerifyInput): number {
  return num(input.qualifiedQuantity) + num(input.reworkQuantity) + num(input.scrapQuantity);
}

/**
 * 核验提交前的本地拦截：只与**服务端返回的**计划数量/当前可执行上限比较，
 * 不在前端推导权威上限。返回非空字符串时禁止提交。
 */
export function verifyItemBlocker(item: ProductionTaskItemView, input: VerifyInput): string | null {
  const completed = completedQuantity(input);
  if (completed > item.plannedQuantity) {
    return `本次完成不得超过计划数量 ${item.plannedQuantity}`;
  }
  if (completed > item.executableQuantity) {
    return `超过当前可执行上限 ${item.executableQuantity}`;
  }
  return null;
}

/** 一次提交整个任务的明细数组（不逐条发送多个写请求）。 */
export function verifySubmitItems(
  items: { item: ProductionTaskItemView; input: VerifyInput; note?: string }[],
): VerifyItemRequest[] {
  return items.map(entry => ({
    taskItemId: entry.item.id,
    qualifiedQuantity: num(entry.input.qualifiedQuantity),
    reworkQuantity: num(entry.input.reworkQuantity),
    scrapQuantity: num(entry.input.scrapQuantity),
    note: entry.note,
  }));
}

/** 整批核验失败：明确「未产生任何事实」，绝不显示假成功。 */
export function describeVerifyFailure(error: unknown): string {
  if (error instanceof YumiApiError) {
    return `整批核验失败，未产生任何事实（${error.code}，requestId: ${error.requestId}）`;
  }
  return '整批核验失败，未产生任何事实';
}

/** 写方法集合：页面据此证明取消/返回/刷新/切换周历只读。 */
export const WRITE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

export interface ReadRequest {
  method: 'GET';
  path: string;
}

export function isWriteRequest(request: { method: string }): boolean {
  return WRITE_METHODS.has(request.method.toUpperCase());
}

export interface WorkbenchReadParams {
  dateFrom?: string;
  dateTo?: string;
  employeeId?: number;
  workTypeId?: number;
  taskType?: string;
  status?: string;
  orderId?: number;
  productId?: number;
}

function queryString(params: Record<string, string | number | undefined>): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') {
      query.set(key, String(value));
    }
  }
  return query.toString();
}

/** 工作台刷新/切换周历使用的请求集合：全部为只读 GET（含当周每日超额任务提醒）。 */
export function buildWorkbenchReads(params: WorkbenchReadParams): ReadRequest[] {
  const overtimeReads: ReadRequest[] = [];
  if (params.dateFrom && params.dateTo) {
    let cursor = dayjs(params.dateFrom);
    const end = dayjs(params.dateTo);
    while (!cursor.isAfter(end)) {
      const date = cursor.format('YYYY-MM-DD');
      overtimeReads.push({ method: 'GET', path: `/api/overtime-tasks?taskDate=${date}` });
      cursor = cursor.add(1, 'day');
    }
  }
  return [
    { method: 'GET', path: `/api/production-tasks?${queryString({ ...params })}` },
    {
      method: 'GET',
      path: `/api/other-schedules?${queryString({
        dateFrom: params.dateFrom,
        dateTo: params.dateTo,
        employeeId: params.employeeId,
      })}`,
    },
    { method: 'GET', path: '/api/production-reminders/incomplete?' },
    ...overtimeReads,
  ];
}

/** 统一新建页加载来源/额度上下文使用的请求集合：全部为只读 GET。 */
export function buildCreatePageReads(orderItemId?: number): ReadRequest[] {
  return [
    { method: 'GET', path: '/api/employees?status=ACTIVE' },
    { method: 'GET', path: '/api/orders?status=CONFIRMED' },
    { method: 'GET', path: '/api/settings/static-data/WORK_TYPE' },
    { method: 'GET', path: `/api/rework-sources?${queryString({ orderItemId })}` },
    { method: 'GET', path: `/api/production-quantity-returns?${queryString({ orderItemId })}` },
  ];
}

/** 取消/放弃新建表单、返回工作台：不发起任何请求，更不写。 */
export function abandonCreateForm(): ReadRequest[] {
  return [];
}

/** 统一新建入口的类型选择（表单内选择，不是四个主创建按钮）。 */
export type CreateScheduleType = 'NORMAL' | 'REWORK' | 'OVERTIME' | 'OTHER';

export const CREATE_TYPE_OPTIONS: { value: CreateScheduleType; label: string }[] = [
  { value: 'NORMAL', label: '正常生产（NORMAL）' },
  { value: 'REWORK', label: '返工（REWORK，工序内部）' },
  { value: 'OVERTIME', label: '超额预占（执行当天，来源为未来正常明细）' },
  { value: 'OTHER', label: '其他排班（分钟事实，不产生商品数量）' },
];
