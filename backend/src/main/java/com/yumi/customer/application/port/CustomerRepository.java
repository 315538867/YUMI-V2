package com.yumi.customer.application.port;

import com.yumi.customer.api.CustomerStatus;
import java.util.List;
import java.util.Optional;

/** 客户持久化端口。 */
public interface CustomerRepository {

    Optional<CustomerRecord> findByBusinessId(String customerBusinessId);

    Optional<CustomerRecord> findByNo(String customerNo);

    List<CustomerRecord> search(String keyword, CustomerStatus status, int offset, int limit);

    long count(String keyword, CustomerStatus status);

    void insert(CustomerRecord record);

    /**
     * 修改资料：内容版本与行版本同时递增。
     *
     * @return 是否命中期望行版本
     */
    boolean updateDetails(
            String customerBusinessId,
            String name,
            String contactName,
            String phone,
            String defaultAddress,
            long expectedRowVersion,
            java.time.Instant now,
            String operatorId);

    boolean updateStatus(
            String customerBusinessId, CustomerStatus status, long expectedRowVersion,
            java.time.Instant now, String operatorId);

    void insertChangeFact(ChangeFactRecord fact);

    List<ChangeFactRecord> findChanges(String customerBusinessId);

    /** 变化事实（模块内部类型）。 */
    record ChangeFactRecord(
            String factBusinessId,
            String customerBusinessId,
            long versionBefore,
            long versionAfter,
            String operationType,
            String changedFieldsJson,
            String reasonText,
            java.time.Instant occurredAt,
            String operatorId) {
    }
}
