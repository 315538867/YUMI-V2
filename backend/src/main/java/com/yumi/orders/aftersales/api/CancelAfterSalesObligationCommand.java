package com.yumi.orders.aftersales.api;

import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.ResponsibilityType;
import com.yumi.production.api.ReturnDispositionType;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 取消售后义务：服务端按接收明细与生产分支核对池可用、已路由终态和已交付数量。
 * 仅池可用数量新增处置；已路由终态通过修复缺口变更解决，不重复退出。
 */
public record CancelAfterSalesObligationCommand(
        String idempotencyKey,
        String obligationBusinessId,
        ReturnDispositionType poolDispositionType,
        PhysicalDestination poolPhysicalDestination,
        ResponsibilityType responsibilityType,
        String responsibleSubjectBusinessId,
        String waivedReason,
        String offlineInventoryName,
        String offlineInventoryNote,
        String handedOverBy,
        String receivedBy,
        LocalDateTime transferredAt,
        LocalDate businessDate,
        String reasonText) {

    public CancelAfterSalesObligationCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("取消义务必须携带幂等键");
        }
        if (obligationBusinessId == null || obligationBusinessId.isBlank()) {
            throw new IllegalArgumentException("取消义务必须指定售后义务");
        }
        if (businessDate == null || reasonText == null || reasonText.isBlank()) {
            throw new IllegalArgumentException("取消义务必须填写业务日期和原因");
        }
    }
}
