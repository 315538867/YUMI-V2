package com.yumi.production.api;

/**
 * 线下库存单向移交（订单减量、取消或售后义务释放造成的多余制品）。
 *
 * <p>移交必须给出业务原因、线下库存名称、移交人与接收人、移交时间；移交后数量进入
 * {@code TRANSFERRED_OFFLINE_INVENTORY} 终态，不能被取回、消费或更正。
 */
public record TransferProductionToOfflineCommand(
        String productionSourceBusinessId,
        int quantity,
        TransferReason reasonCode,
        String upstreamChangeBusinessId,
        String offlineInventoryName,
        String offlineInventoryNote,
        String handedOverBy,
        String receivedBy,
        java.time.LocalDateTime transferredAt) {

    public TransferProductionToOfflineCommand {
        if (productionSourceBusinessId == null || productionSourceBusinessId.isBlank()) {
            throw new IllegalArgumentException("生产来源标识不能为空");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("移交数量必须大于 0");
        }
        if (reasonCode == null) {
            throw new IllegalArgumentException("移交必须明确业务原因");
        }
        if (offlineInventoryName == null || offlineInventoryName.isBlank()) {
            throw new IllegalArgumentException("移交必须填写线下库存名称");
        }
        if (handedOverBy == null || handedOverBy.isBlank() || receivedBy == null || receivedBy.isBlank()) {
            throw new IllegalArgumentException("移交必须填写移交人与接收人");
        }
        if (transferredAt == null) {
            throw new IllegalArgumentException("移交必须填写移交时间");
        }
    }
}
