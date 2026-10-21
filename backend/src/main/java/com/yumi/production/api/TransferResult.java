package com.yumi.production.api;

/** 移交结果：移交事实与终态退出。 */
public record TransferResult(
        String transferFactBusinessId,
        String terminalExitBusinessId,
        String productionSourceBusinessId,
        int quantity,
        int remainingTransferable) {
}
