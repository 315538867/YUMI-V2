package com.yumi.shared.error;

import org.springframework.http.HttpStatus;

/**
 * 稳定错误码目录。具体码与状态码映射依据 design.md 第 3、6 节冻结。
 */
public enum ErrorCode {
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "需要管理员认证"),
    AUTH_INVALID(HttpStatus.UNAUTHORIZED, "用户名或密码错误"),

    VALIDATION_INVALID(HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    QUANTITY_INVALID(HttpStatus.BAD_REQUEST, "数量不合法"),
    VERIFICATION_EQUATION_INVALID(HttpStatus.BAD_REQUEST, "核验数量等式不成立"),
    OVERTIME_DATE_INVALID(HttpStatus.BAD_REQUEST, "超额任务只能在执行当天创建"),
    REPORT_TYPE_INVALID(HttpStatus.BAD_REQUEST, "不支持的报表类型"),

    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "订单不存在"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "接口不存在"),

    SNAPSHOT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "快照生成失败"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误"),

    MIGRATION_INVALID(HttpStatus.SERVICE_UNAVAILABLE, "数据库迁移校验失败"),

    STATE_DISABLED(HttpStatus.CONFLICT, "商品已停用"),
    STATE_NOT_EDITABLE(HttpStatus.CONFLICT, "当前状态不可编辑"),
    STATE_NOT_CONFIRMABLE(HttpStatus.CONFLICT, "当前状态不可确认"),
    STATE_NOT_CHANGEABLE(HttpStatus.CONFLICT, "当前状态不可变更"),
    STATE_CANCEL_NOT_ALLOWED(HttpStatus.CONFLICT, "当前状态不允许取消"),
    STATE_CANNOT_CANCEL(HttpStatus.CONFLICT, "来源已被消费，不可取消"),
    STATE_NOT_CANCELABLE(HttpStatus.CONFLICT, "当前状态不可取消"),
    STATE_ALREADY_VERIFIED(HttpStatus.CONFLICT, "已完成核验，不可重复核验"),
    STATE_CLOSED_REQUIRES_CORRECTION(HttpStatus.CONFLICT, "订单已关闭，只能通过更正处理"),

    CONFLICT_VERSION(HttpStatus.CONFLICT, "数据已被其他操作修改，请刷新后重试"),
    CONFLICT_DUPLICATE(HttpStatus.CONFLICT, "存在重复记录"),
    CONFLICT_IDEMPOTENCY(HttpStatus.CONFLICT, "幂等键已被不同请求使用"),
    CONFLICT_REFERENCED(HttpStatus.CONFLICT, "已被商品引用，禁止删除"),

    QUANTITY_BELOW_SHIPPED(HttpStatus.CONFLICT, "新数量不得低于累计有效发货"),
    QUANTITY_REQUIRES_DISPOSITION(HttpStatus.CONFLICT, "超出部分必须逐项处理"),
    QUANTITY_NOT_EXECUTABLE(HttpStatus.CONFLICT, "超过当前可执行数量"),

    SOURCE_ALREADY_CONSUMED(HttpStatus.CONFLICT, "来源已被消费"),
    SOURCE_INSUFFICIENT(HttpStatus.CONFLICT, "来源余额不足"),
    SOURCE_INVALID(HttpStatus.CONFLICT, "来源不合法"),
    CAPACITY_EXCEEDED(HttpStatus.CONFLICT, "超过产品当日最大产能"),

    STOCK_NEGATIVE(HttpStatus.CONFLICT, "库存数量不得为负"),
    STOCK_INSUFFICIENT(HttpStatus.CONFLICT, "库存不足"),

    FACT_IMMUTABLE(HttpStatus.CONFLICT, "事实记录不可修改"),
    REFUND_PENDING(HttpStatus.CONFLICT, "存在待处理退款"),
    EMPLOYEE_NOT_ELIGIBLE(HttpStatus.CONFLICT, "员工不具备执行资格"),
    OVERTIME_RESERVATION_EXCEEDED(HttpStatus.CONFLICT, "超额预占数量超过可用余额"),

    SHIPMENT_EXCEEDS_AVAILABLE(HttpStatus.CONFLICT, "本次发货超过可发货数量"),
    SHIPMENT_EXCEEDS_DEMAND(HttpStatus.CONFLICT, "累计发货超过有效订购数量"),
    SHIPMENT_AFTER_SALES_LINKED(HttpStatus.CONFLICT, "发货批次已被售后占用"),
    CORRECTION_REPLACEMENT_REQUIRED(HttpStatus.CONFLICT, "无法立即更正，需先处理售后来源"),

    PAYMENT_DRAFT_FORBIDDEN(HttpStatus.CONFLICT, "草稿订单不可登记收款"),
    REFUND_EXCEEDS_RECEIPTS(HttpStatus.CONFLICT, "退款超过累计收款"),
    REFUND_REFERENCE_REQUIRED(HttpStatus.CONFLICT, "退款必须关联来源"),
    CLOSE_FULFILLMENT_PENDING(HttpStatus.CONFLICT, "履约未完成，不能关闭"),
    CLOSE_SETTLEMENT_PENDING(HttpStatus.CONFLICT, "应收未结清，不能关闭"),
    CLOSE_REFUND_PENDING(HttpStatus.CONFLICT, "存在待退款，不能关闭"),

    AFTER_SALES_SOURCE_INVALID(HttpStatus.CONFLICT, "售后来源无效"),
    AFTER_SALES_QUANTITY_EXCEEDED(HttpStatus.CONFLICT, "售后数量超过剩余可受理量"),
    AFTER_SALES_EQUATION_INVALID(HttpStatus.CONFLICT, "退回数量等式不成立"),
    AFTER_SALES_REPLACEMENT_INSUFFICIENT(HttpStatus.CONFLICT, "售后可补发数量不足");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
