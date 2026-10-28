package com.yumi.customer.application;

import com.yumi.customer.api.CustomerSnapshotRef;
import com.yumi.customer.api.CustomerSnapshotService;
import com.yumi.customer.api.CustomerSnapshotView;
import com.yumi.customer.api.CustomerStatus;
import com.yumi.customer.api.DeliveryContactOverride;
import com.yumi.customer.application.port.CustomerRecord;
import com.yumi.customer.application.port.CustomerRepository;
import com.yumi.customer.application.port.CustomerSnapshotRecord;
import com.yumi.customer.application.port.CustomerSnapshotRepository;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.value.ContentFingerprint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 客户快照实现。
 *
 * <p>规则来源：`docs/一期/08-客户模块.md` §5、§9：
 * <ul>
 *   <li>同一客户、同一内容版本、相同收货覆盖值只产生一个快照；唯一键裁决并发；</li>
 *   <li>覆盖值只进入本次快照，不修改客户默认资料；</li>
 *   <li>取得快照时客户必须为 {@code ACTIVE}；解析历史快照不受当前停用影响；</li>
 *   <li>快照不可修改，错误订单通过订单变更处理。</li>
 * </ul>
 */
public class CustomerSnapshotServiceImpl implements CustomerSnapshotService {

    /** 未覆盖收货信息时使用的固定空值指纹，保证唯一键稳定。 */
    public static final ContentFingerprint NO_OVERRIDE_FINGERPRINT =
            ContentFingerprint.of(sha256("no-override"));

    private final CustomerRepository customers;

    private final CustomerSnapshotRepository snapshots;

    private final AuthorizationService authorization;

    private final CurrentActorProvider currentActor;

    private final Clock clock;

    /** 供 Spring 装配使用：显式注入两个仓储。 */
    public CustomerSnapshotServiceImpl(
            CustomerRepository customers,
            CustomerSnapshotRepository snapshots,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            Clock clock) {
        this.customers = customers;
        this.snapshots = snapshots;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @Override
    public CustomerSnapshotRef acquireCurrentCustomerSnapshot(
            String customerBusinessId, DeliveryContactOverride override) {
        authorization.require(PermissionCodes.CUSTOMER_READ, ResourceScope.GLOBAL);
        if (customerBusinessId == null || customerBusinessId.isBlank()) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_CUSTOMER", "客户标识不能为空");
        }
        DeliveryContactOverride effective = override == null ? DeliveryContactOverride.NONE : override;

        CustomerRecord customer = customers.findByBusinessId(customerBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "CUSTOMER_NOT_FOUND", "客户不存在"));
        if (customer.status() != CustomerStatus.ACTIVE) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "CUSTOMER_INACTIVE", "客户已停用，不能用于新订单");
        }

        ContentFingerprint overrideFingerprint = effective.isEmpty()
                ? NO_OVERRIDE_FINGERPRINT
                : ContentFingerprint.of(sha256(effective.canonical()));

        return snapshots
                .find(customer.customerBusinessId(), customer.contentVersion(), overrideFingerprint)
                .map(CustomerSnapshotServiceImpl::toRef)
                .orElseGet(() -> createSnapshot(customer, effective, overrideFingerprint));
    }

    @Override
    public CustomerSnapshotView resolve(CustomerSnapshotRef ref) {
        authorization.require(PermissionCodes.CUSTOMER_READ, ResourceScope.GLOBAL);
        if (ref == null) {
            throw new IllegalArgumentException("快照引用不能为空");
        }
        CustomerSnapshotRecord record = snapshots.findByBusinessId(ref.snapshotBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "CUSTOMER_SNAPSHOT_NOT_FOUND", "客户快照不存在"));
        return new CustomerSnapshotView(
                toRef(record), record.customerNo(), record.name(), record.contactName(),
                record.phone(), record.address());
    }

    @Override
    public CustomerSnapshotView resolveById(String snapshotBusinessId) {
        authorization.require(PermissionCodes.CUSTOMER_READ, ResourceScope.GLOBAL);
        CustomerSnapshotRecord record = snapshots.findByBusinessId(snapshotBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "CUSTOMER_SNAPSHOT_NOT_FOUND", "客户快照不存在"));
        return new CustomerSnapshotView(
                toRef(record), record.customerNo(), record.name(), record.contactName(),
                record.phone(), record.address());
    }

    private CustomerSnapshotRef createSnapshot(
            CustomerRecord customer, DeliveryContactOverride override, ContentFingerprint overrideFingerprint) {
        Instant now = clock.instant();
        long nextVersion = snapshots.maxSnapshotVersion(customer.customerBusinessId()) + 1;

        String address = override.address() == null ? customer.defaultAddress() : override.address().strip();
        String contactName = override.recipientName() == null
                ? customer.contactName() : override.recipientName().strip();
        String phone = override.phone() == null ? customer.phone() : override.phone().strip();

        CustomerSnapshotRecord record = new CustomerSnapshotRecord(
                0L,
                "CSNAP-" + UUID.randomUUID(),
                customer.customerBusinessId(),
                nextVersion,
                customer.contentVersion(),
                overrideFingerprint,
                customer.customerNo(),
                customer.name(),
                contactName,
                phone,
                address,
                ContentFingerprint.of(sha256(
                        customer.customerNo() + "|" + customer.name() + "|" + contactName + "|" + phone
                                + "|" + address + "|" + customer.contentVersion() + "|"
                                + overrideFingerprint.value())),
                now);

        try {
            snapshots.insert(record);
            return toRef(record);
        } catch (com.yumi.customer.infrastructure.JdbcCustomerSnapshotRepository.SnapshotAlreadyExists conflict) {
            // 并发创建：唯一键裁决，重新读取既有快照
            return snapshots
                    .find(customer.customerBusinessId(), customer.contentVersion(), overrideFingerprint)
                    .map(CustomerSnapshotServiceImpl::toRef)
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.CONCURRENT_CONFLICT, "CUSTOMER_SNAPSHOT_CONFLICT",
                            "客户快照并发创建冲突，请重试"));
        }
    }

    private static CustomerSnapshotRef toRef(CustomerSnapshotRecord record) {
        return new CustomerSnapshotRef(
                record.snapshotBusinessId(),
                record.customerBusinessId(),
                record.snapshotVersion(),
                record.sourceContentVersion(),
                record.overrideFingerprint(),
                record.contentFingerprint());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法", exception);
        }
    }
}
