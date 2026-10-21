package com.yumi.orders.aftersales.api;

import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.ResponsibilityType;
import com.yumi.production.api.ReturnDispositionType;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 退回制品池处置命令：orders 鉴权并校验义务/接收明细后，通过 production 参与接口同事务写入。
 *
 * @param responsibilityType 报废责任或免除；仅报废必填
 */
public record DisposeReturnedProductCommand(
        String idempotencyKey,
        String receiptItemBusinessId,
        int quantity,
        ReturnDispositionType dispositionType,
        PhysicalDestination physicalDestination,
        LocalDate businessDate,
        String reasonText,
        ResponsibilityType responsibilityType,
        String responsibleSubjectBusinessId,
        String waivedReason,
        String offlineInventoryName,
        String offlineInventoryNote,
        String handedOverBy,
        String receivedBy,
        LocalDateTime transferredAt) {

    public DisposeReturnedProductCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("池处置必须携带幂等键");
        }
        if (receiptItemBusinessId == null || receiptItemBusinessId.isBlank()) {
            throw new IllegalArgumentException("池处置必须指定退回接收明细");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("池处置数量必须大于零");
        }
        if (dispositionType == null || dispositionType == ReturnDispositionType.RETURN_TO_CUSTOMER) {
            throw new IllegalArgumentException("池处置只接受报废或非报废义务释放；原样退还应使用售后交付命令");
        }
        if (physicalDestination == null || businessDate == null
                || reasonText == null || reasonText.isBlank()) {
            throw new IllegalArgumentException("池处置必须明确去向、业务日期和原因");
        }
    }
}
