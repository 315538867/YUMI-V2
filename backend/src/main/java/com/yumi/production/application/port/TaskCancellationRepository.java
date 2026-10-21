package com.yumi.production.application.port;

/** 取消事实持久化端口。 */
public interface TaskCancellationRepository {

    void insertCancellation(
            String taskBusinessId, String reasonCode, String reasonText,
            java.time.Instant occurredAt, String operatorId);
}
