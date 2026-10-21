package com.yumi.production.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 消费与线下移交持久化端口。 */
public interface ConsumptionRepository {

    void lockSource(String productionSourceBusinessId);

    int consumedQuantityOf(String productionSourceBusinessId);

    int transferredQuantityOf(String productionSourceBusinessId);

    int activeReservationOf(String productionSourceBusinessId);

    /** 同一交付来源的全部有效消费事实（含 CONSUME 与 REVERSE），按发生顺序排列。 */
    List<ConsumptionRecord> findConsumptions(
            String sourceModule, String sourceType, String sourceBusinessId);

    /** 某条消费事实已被有效反向的数量。 */
    int reversedQuantityOf(String consumptionFactBusinessId);

    void insertConsumption(ConsumptionRecord record);

    void insertTransfer(TransferRecord record);

    Optional<TransferRecord> findTransfer(String transferFactBusinessId);

    List<ConsumptionRecord> listConsumptions(String productionSourceBusinessId);

    /** 消费事实。 */
    record ConsumptionRecord(
            String consumptionFactBusinessId, String effectType, String reversesFactBusinessId,
            String sourceModule, String sourceType, String sourceBusinessId,
            String productionSourceBusinessId, String physicalRootSourceBusinessId,
            String demandBusinessId, String productBusinessId, int quantity, Instant occurredAt,
            String operatorId) {
    }

    /** 线下移交事实。 */
    record TransferRecord(
            String transferFactBusinessId, String terminalExitBusinessId,
            String productionSourceBusinessId, String physicalRootSourceBusinessId,
            String demandBusinessId, String productBusinessId, String reasonCode,
            String upstreamChangeBusinessId, String offlineInventoryName, String offlineInventoryNote,
            String handedOverBy, String receivedBy, Instant transferredAt, int quantity,
            Instant occurredAt, String operatorId) {
    }
}
