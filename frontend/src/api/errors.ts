/**
 * 与后端 `com.yumi.shared.error.ErrorCode` 对齐的稳定错误码目录（design.md 第 3、6 节）。
 * 前端只按 code 分支，不解析 message 文案。
 */
export const ERROR_CODES = [
  'AUTH_REQUIRED',
  'AUTH_INVALID',
  'VALIDATION_INVALID',
  'QUANTITY_INVALID',
  'REWORK_TARGET_INVALID',
  'VERIFICATION_EQUATION_INVALID',
  'OVERTIME_DATE_INVALID',
  'REPORT_TYPE_INVALID',
  'ORDER_NOT_FOUND',
  'NOT_FOUND',
  'SNAPSHOT_FAILED',
  'INTERNAL_ERROR',
  'MIGRATION_INVALID',
  'STATE_DISABLED',
  'STATE_NOT_EDITABLE',
  'STATE_NOT_CONFIRMABLE',
  'STATE_NOT_CHANGEABLE',
  'STATE_CANCEL_NOT_ALLOWED',
  'STATE_CANNOT_CANCEL',
  'STATE_NOT_CANCELABLE',
  'STATE_ALREADY_VERIFIED',
  'STATE_CLOSED_REQUIRES_CORRECTION',
  'CONFLICT_VERSION',
  'CONFLICT_DUPLICATE',
  'CONFLICT_IDEMPOTENCY',
  'CONFLICT_REFERENCED',
  'QUANTITY_BELOW_SHIPPED',
  'QUANTITY_REQUIRES_DISPOSITION',
  'QUANTITY_NOT_EXECUTABLE',
  'SOURCE_ALREADY_CONSUMED',
  'SOURCE_INSUFFICIENT',
  'STOCK_NEGATIVE',
  'STOCK_INSUFFICIENT',
  'FACT_IMMUTABLE',
  'REFUND_PENDING',
  'EMPLOYEE_NOT_ELIGIBLE',
  'OVERTIME_RESERVATION_EXCEEDED',
  'SHIPMENT_EXCEEDS_AVAILABLE',
  'SHIPMENT_EXCEEDS_DEMAND',
  'SHIPMENT_AFTER_SALES_LINKED',
  'CORRECTION_REPLACEMENT_REQUIRED',
  'PAYMENT_DRAFT_FORBIDDEN',
  'REFUND_EXCEEDS_RECEIPTS',
  'REFUND_REFERENCE_REQUIRED',
  'CLOSE_FULFILLMENT_PENDING',
  'CLOSE_SETTLEMENT_PENDING',
  'CLOSE_REFUND_PENDING',
  'AFTER_SALES_SOURCE_INVALID',
  'AFTER_SALES_QUANTITY_EXCEEDED',
  'AFTER_SALES_EQUATION_INVALID',
  'AFTER_SALES_REPLACEMENT_INSUFFICIENT',
] as const;

export type ErrorCode = (typeof ERROR_CODES)[number];

export interface ApiFieldError {
  field: string;
  message: string;
}

export interface ApiErrorBody {
  code: string;
  message: string;
  fieldErrors: ApiFieldError[];
  requestId: string;
  /** 统一信封的业务负载；错误时为 null。HTTP 封装层负责解包，调用处不感知。 */
  data?: unknown;
}

/** 统一错误：携带稳定 code、字段定位与服务端 requestId。 */
export class YumiApiError extends Error {
  readonly code: string;
  readonly fieldErrors: ApiFieldError[];
  readonly requestId: string;
  readonly status: number;

  constructor(body: ApiErrorBody, status: number) {
    super(body.message);
    this.name = 'YumiApiError';
    this.code = body.code;
    this.fieldErrors = body.fieldErrors ?? [];
    this.requestId = body.requestId;
    this.status = status;
  }
}

export function isErrorCode(value: string): value is ErrorCode {
  return (ERROR_CODES as readonly string[]).includes(value);
}

const FALLBACK_ACTION = '操作未完成，请稍后重试或联系管理员';

/** 按错误码返回可执行动作提示；未知码回退默认提示。 */
const ERROR_ACTIONS: Record<ErrorCode, string> = {
  AUTH_REQUIRED: '请重新登录',
  AUTH_INVALID: '请检查用户名和密码后重试',
  VALIDATION_INVALID: '请修正标红字段后重试',
  QUANTITY_INVALID: '请修正数量后重试',
  REWORK_TARGET_INVALID: '请重新选择返工目标工序',
  VERIFICATION_EQUATION_INVALID: '请核对完成、合格、返工、报废数量',
  OVERTIME_DATE_INVALID: '超额任务只能在执行当天创建，请刷新后重试',
  REPORT_TYPE_INVALID: '请选择有效的报表类型',
  ORDER_NOT_FOUND: '订单不存在，请返回订单列表',
  NOT_FOUND: '页面或接口不存在，请返回工作台',
  SNAPSHOT_FAILED: '快照生成失败，请稍后重试',
  INTERNAL_ERROR: '服务器内部错误，请稍后重试',
  MIGRATION_INVALID: '系统发布校验未通过，请联系管理员',
  STATE_DISABLED: '商品已停用，请选择其他商品',
  STATE_NOT_EDITABLE: '当前状态不可编辑，请刷新页面',
  STATE_NOT_CONFIRMABLE: '当前状态不可确认，请刷新后重试',
  STATE_NOT_CHANGEABLE: '当前状态不可变更，请刷新后重试',
  STATE_CANCEL_NOT_ALLOWED: '当前状态不可取消，可改用订单变更',
  STATE_CANNOT_CANCEL: '来源已被消费，不可取消',
  STATE_NOT_CANCELABLE: '当前状态不可取消，请刷新后重试',
  STATE_ALREADY_VERIFIED: '已完成核验，无需重复操作',
  STATE_CLOSED_REQUIRES_CORRECTION: '订单已关闭，请通过更正处理',
  CONFLICT_VERSION: '请刷新后重试',
  CONFLICT_DUPLICATE: '存在重复记录，请检查后重试',
  CONFLICT_IDEMPOTENCY: '相同幂等键已用于不同请求，请更换请求标识',
  CONFLICT_REFERENCED: '该条目已被商品引用，不能删除',
  QUANTITY_BELOW_SHIPPED: '新数量不得低于累计有效发货，请上调数量',
  QUANTITY_REQUIRES_DISPOSITION: '请逐项处理超出部分后再确认',
  QUANTITY_NOT_EXECUTABLE: '超过当前可执行数量，请调整计划量',
  SOURCE_ALREADY_CONSUMED: '来源已被消费，请刷新后重试',
  SOURCE_INSUFFICIENT: '来源余额不足，请选择其他来源',
  STOCK_NEGATIVE: '库存数量不得为负，请修正数量',
  STOCK_INSUFFICIENT: '库存不足，请转生产或选择其他批次',
  FACT_IMMUTABLE: '事实记录不可修改',
  REFUND_PENDING: '存在待处理退款，请先处理退款',
  EMPLOYEE_NOT_ELIGIBLE: '员工不具备执行资格，请更换员工',
  OVERTIME_RESERVATION_EXCEEDED: '超额预占超过可用余额，请调整数量',
  SHIPMENT_EXCEEDS_AVAILABLE: '超过可发货数量，请调整本次发货',
  SHIPMENT_EXCEEDS_DEMAND: '累计发货超过有效订购，不可确认',
  SHIPMENT_AFTER_SALES_LINKED: '批次已被售后占用，请先处理售后',
  CORRECTION_REPLACEMENT_REQUIRED: '无法立即更正，请按引导先处理售后',
  PAYMENT_DRAFT_FORBIDDEN: '草稿订单不可登记收款，请先确认订单',
  REFUND_EXCEEDS_RECEIPTS: '退款超过累计收款，请调整金额',
  REFUND_REFERENCE_REQUIRED: '退款必须关联来源，请补充来源信息',
  CLOSE_FULFILLMENT_PENDING: '履约未完成，暂不能关闭',
  CLOSE_SETTLEMENT_PENDING: '应收未结清，暂不能关闭',
  CLOSE_REFUND_PENDING: '存在待退款，暂不能关闭',
  AFTER_SALES_SOURCE_INVALID: '售后来源无效，请刷新后重试',
  AFTER_SALES_QUANTITY_EXCEEDED: '售后数量超过剩余可受理量，请调整数量',
  AFTER_SALES_EQUATION_INVALID: '退回数量等式不成立，请核对退回、返工、报废数量',
  AFTER_SALES_REPLACEMENT_INSUFFICIENT: '售后可补发数量不足，请等待补货后再发',
};

export function errorAction(code: string): string {
  return isErrorCode(code) ? ERROR_ACTIONS[code] : FALLBACK_ACTION;
}

/** 页面级统一错误文案：动作提示 + requestId，供 message/Alert 直接展示。 */
export function describeApiError(error: unknown): string {
  if (error instanceof YumiApiError) {
    return `${errorAction(error.code)}（requestId: ${error.requestId}）`;
  }
  return '网络异常，请稍后重试';
}
