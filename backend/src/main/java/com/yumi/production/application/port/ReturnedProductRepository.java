package com.yumi.production.application.port;

import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.ReturnDispositionType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/** 售后退回制品池持久化端口。 */
public interface ReturnedProductRepository {

    Optional<ReturnedSourceRecord> findReturnedSource(String returnedSourceBusinessId);

    Optional<ReturnedSourceRecord> lockReturnedSource(String returnedSourceBusinessId);

    Optional<ReturnedSourceRecord> findByReceiptItem(String receiptItemBusinessId);

    void insertReturnedSource(ReturnedSourceRecord record);

    void updateReturnedSourceQuantities(
            String returnedSourceBusinessId, int availableQuantity, int routedRepairQuantity,
            int disposedQuantity, String status);

    void insertReentryFact(ReentryFact record);

    QuantityProjections.PhysicalProjection lockPhysicalRoot(String physicalRootSourceBusinessId);

    void insertPoolConsumption(PoolConsumption record);

    void insertPoolDisposition(PoolDisposition record);

    void insertPoolTransfer(PoolTransfer record);

    int countExecutionEvidence(String physicalRootSourceBusinessId, String subjectBusinessId);

    record ReturnedSourceRecord(
            String returnedSourceBusinessId, String afterSalesReturnReceiptItemBusinessId,
            String physicalRootSourceBusinessId, String priorDeliverySourceType,
            String priorProductionSourceBusinessId, String priorReturnedSourceBusinessId,
            String priorConsumptionFactBusinessId, String productSnapshotRef,
            String schemeSnapshotRef, String returnedStageCode, int originalQuantity,
            int availableQuantity, int routedRepairQuantity, int disposedQuantity,
            String status, long rowVersion, Instant createdAt) {
    }

    record ReentryFact(
            String factBusinessId, String returnedSourceBusinessId,
            String afterSalesReturnReceiptItemBusinessId, String physicalRootSourceBusinessId,
            String priorDeliverySourceType, String priorProductionSourceBusinessId,
            String priorReturnedSourceBusinessId, String priorConsumptionFactBusinessId,
            int quantity, Instant occurredAt, String operatorId) {
    }

    record PoolConsumption(
            String consumptionFactBusinessId, String returnedSourceBusinessId,
            String physicalRootSourceBusinessId, String sourceModule, String sourceType,
            String sourceBusinessId, int quantity, Instant occurredAt, String operatorId) {
    }

    record PoolDisposition(
            String factBusinessId, String returnedSourceBusinessId,
            String physicalRootSourceBusinessId, ReturnDispositionType dispositionType,
            PhysicalDestination physicalDestination, String terminalExitBusinessId, int quantity,
            String consumptionFactBusinessId, String offlineTransferFactBusinessId,
            String reasonCode, String reasonText, LocalDate businessDate, Instant occurredAt,
            String operatorId) {
    }

    record PoolTransfer(
            String transferFactBusinessId, String terminalExitBusinessId,
            String returnedSourceBusinessId, String physicalRootSourceBusinessId,
            String dispositionFactBusinessId, String offlineInventoryName,
            String offlineInventoryNote, String handedOverBy, String receivedBy,
            LocalDateTime transferredAt, int quantity, Instant occurredAt, String operatorId) {
    }
}
