package com.yumi.production.api;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 退回制品池处置命令。
 *
 * @param consumerModule 池来源消费者模块；原样退还时必填
 * @param consumerType 消费者类型；原样退还时为售后交付
 * @param consumerBusinessId 售后交付事实标识
 * @param responsibilityType 报废责任；仅报废必填
 * @param offlineInventoryName 转线下时的接收仓/位置
 */
public record ReturnDispositionCommand(
        String returnedSourceBusinessId,
        ReturnDispositionType dispositionType,
        PhysicalDestination physicalDestination,
        int quantity,
        LocalDate businessDate,
        String reasonText,
        String consumerModule,
        String consumerType,
        String consumerBusinessId,
        ResponsibilityType responsibilityType,
        String responsibleSubjectBusinessId,
        String waivedReason,
        String offlineInventoryName,
        String offlineInventoryNote,
        String handedOverBy,
        String receivedBy,
        LocalDateTime transferredAt) {
}
