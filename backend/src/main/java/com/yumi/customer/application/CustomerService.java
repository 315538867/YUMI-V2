package com.yumi.customer.application;

import com.yumi.customer.api.ChangeCustomerStatusCommand;
import com.yumi.customer.api.CreateCustomerCommand;
import com.yumi.customer.api.CustomerApplicationService;
import com.yumi.customer.api.CustomerQuery;
import com.yumi.customer.api.CustomerRef;
import com.yumi.customer.api.CustomerStatus;
import com.yumi.customer.api.CustomerView;
import com.yumi.customer.api.DeliveryContactOverride;
import com.yumi.customer.api.UpdateCustomerCommand;
import com.yumi.customer.application.port.CustomerRecord;
import com.yumi.customer.application.port.CustomerRepository;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.numbering.BusinessNumberService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 客户应用服务：创建、修改、启停与查询。
 *
 * <p>规则来源：`docs/一期/08-客户模块.md` §5、§9、§10：
 * <ul>
 *   <li>客户编号创建后不可修改、不可复用；</li>
 *   <li>停用只禁止新订单引用，不删除历史客户、订单和快照；</li>
 *   <li>资料修改递增内容版本并记录变化事实；</li>
 *   <li>修改与启停携带行版本，冲突明确拒绝；</li>
 *   <li>不提供服务端草稿，一次提交完整资料。</li>
 * </ul>
 *
 * <p>本类不读订单或财务数据，只通过公开查询与快照服务对外协作。
 */
public class CustomerService implements CustomerApplicationService, CustomerQuery {

    /** 客户名称最大长度。 */
    public static final int MAX_NAME_LENGTH = 80;

    /** 联系人最大长度。 */
    public static final int MAX_CONTACT_LENGTH = 40;

    /** 电话长度范围。 */
    public static final int MIN_PHONE_LENGTH = 6;

    public static final int MAX_PHONE_LENGTH = 30;

    /** 默认地址最大长度。 */
    public static final int MAX_ADDRESS_LENGTH = 200;

    private static final Pattern PHONE_ALLOWED = Pattern.compile("[0-9+()\\-\\s]+");

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}]");

    private final CustomerRepository customers;

    private final BusinessNumberService numbering;

    private final AuthorizationService authorization;

    private final CurrentActorProvider currentActor;

    private final Clock clock;

    private final TransactionTemplate transactionTemplate;

    public CustomerService(
            CustomerRepository customers,
            BusinessNumberService numbering,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.customers = customers;
        this.numbering = numbering;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public CustomerRef create(CreateCustomerCommand command) {
        authorization.require(PermissionCodes.CUSTOMER_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new IllegalArgumentException("创建客户命令不能为空");
        }

        String name = requireText(command.name(), "客户名称", MAX_NAME_LENGTH);
        String contactName = requireText(command.contactName(), "联系人", MAX_CONTACT_LENGTH);
        String phone = validatePhone(command.phone());
        String address = requireText(command.defaultAddress(), "默认收货地址", MAX_ADDRESS_LENGTH);

        return transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
            String customerNo = numbering.nextNumber("CUSTOMER", "C-", 6);
            String customerBusinessId = "CUS-" + UUID.randomUUID();

            CustomerRecord record = new CustomerRecord(
                    0L, customerBusinessId, customerNo, name, contactName, phone, address,
                    CustomerStatus.ACTIVE, 1, 1, now, operatorId, now, operatorId);
            customers.insert(record);
            customers.insertChangeFact(new CustomerRepository.ChangeFactRecord(
                    "CHG-" + UUID.randomUUID(), customerBusinessId, 0, 1, "CREATE", null, null, now, operatorId));

            return new CustomerRef(customerBusinessId, customerNo, CustomerStatus.ACTIVE, 1, 1);
        });
    }

    @Override
    public CustomerRef update(UpdateCustomerCommand command) {
        authorization.require(PermissionCodes.CUSTOMER_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new IllegalArgumentException("修改客户命令不能为空");
        }
        String customerBusinessId = requireText(command.customerBusinessId(), "客户标识", 64);
        String name = requireText(command.name(), "客户名称", MAX_NAME_LENGTH);
        String contactName = requireText(command.contactName(), "联系人", MAX_CONTACT_LENGTH);
        String phone = validatePhone(command.phone());
        String address = requireText(command.defaultAddress(), "默认收货地址", MAX_ADDRESS_LENGTH);

        return transactionTemplate.execute(status -> {
            CustomerRecord existing = customers.findByBusinessId(customerBusinessId)
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.NOT_FOUND, "CUSTOMER_NOT_FOUND", "客户不存在"));

            Instant now = clock.instant();
            String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
            boolean changed = !existing.name().equals(name)
                    || !existing.contactName().equals(contactName)
                    || !existing.phone().equals(phone)
                    || !existing.defaultAddress().equals(address);

            if (!changed) {
                return toRef(existing);
            }
            if (!customers.updateDetails(
                    customerBusinessId, name, contactName, phone, address,
                    command.expectedRowVersion(), now, operatorId)) {
                throw new ApiException(
                        ApiErrorType.CONCURRENT_CONFLICT, "CUSTOMER_VERSION_CONFLICT",
                        "客户资料已被其他操作修改，请重新打开后再保存");
            }

            CustomerRecord updated = customers.findByBusinessId(customerBusinessId).orElseThrow();
            customers.insertChangeFact(new CustomerRepository.ChangeFactRecord(
                    "CHG-" + UUID.randomUUID(), customerBusinessId, existing.contentVersion(),
                    updated.contentVersion(), "UPDATE",
                    changedFieldsJson(existing, updated), null, now, operatorId));
            return toRef(updated);
        });
    }

    @Override
    public CustomerRef changeStatus(ChangeCustomerStatusCommand command) {
        authorization.require(PermissionCodes.CUSTOMER_MANAGE, ResourceScope.GLOBAL);
        if (command == null || command.targetStatus() == null) {
            throw new IllegalArgumentException("状态变更命令与目标状态不能为空");
        }
        String customerBusinessId = requireText(command.customerBusinessId(), "客户标识", 64);

        return transactionTemplate.execute(status -> {
            CustomerRecord existing = customers.findByBusinessId(customerBusinessId)
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.NOT_FOUND, "CUSTOMER_NOT_FOUND", "客户不存在"));

            if (existing.status() == command.targetStatus()) {
                return toRef(existing);
            }

            Instant now = clock.instant();
            String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
            if (!customers.updateStatus(
                    customerBusinessId, command.targetStatus(), command.expectedRowVersion(), now, operatorId)) {
                throw new ApiException(
                        ApiErrorType.CONCURRENT_CONFLICT, "CUSTOMER_VERSION_CONFLICT",
                        "客户状态已被其他操作修改，请重新打开后再操作");
            }

            CustomerRecord updated = customers.findByBusinessId(customerBusinessId).orElseThrow();
            customers.insertChangeFact(new CustomerRepository.ChangeFactRecord(
                    "CHG-" + UUID.randomUUID(), customerBusinessId, existing.contentVersion(),
                    updated.contentVersion(),
                    command.targetStatus() == CustomerStatus.ACTIVE ? "REACTIVATE" : "DEACTIVATE",
                    null, null, now, operatorId));
            return toRef(updated);
        });
    }

    @Override
    public CustomerView getCurrent(String customerBusinessId) {
        authorization.require(PermissionCodes.CUSTOMER_READ, ResourceScope.GLOBAL);
        return toView(customers.findByBusinessId(requireText(customerBusinessId, "客户标识", 64))
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "CUSTOMER_NOT_FOUND", "客户不存在")));
    }

    @Override
    public long countActive() {
        authorization.require(PermissionCodes.CUSTOMER_READ, ResourceScope.GLOBAL);
        return customers.count("", CustomerStatus.ACTIVE);
    }

    @Override
    public List<CustomerView> search(String keyword, CustomerStatus status, int page, int size) {
        authorization.require(PermissionCodes.CUSTOMER_READ, ResourceScope.GLOBAL);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        return customers.search(keyword, status, (safePage - 1) * safeSize, safeSize).stream()
                .map(CustomerService::toView)
                .toList();
    }

    @Override
    public List<com.yumi.customer.api.CustomerChangeView> history(String customerBusinessId) {
        authorization.require(PermissionCodes.CUSTOMER_READ, ResourceScope.GLOBAL);
        String id = requireText(customerBusinessId, "客户标识", 64);
        customers.findByBusinessId(id)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "CUSTOMER_NOT_FOUND", "客户不存在"));
        return customers.findChanges(id).stream()
                .map(change -> new com.yumi.customer.api.CustomerChangeView(
                        change.factBusinessId(), change.versionBefore(), change.versionAfter(),
                        change.operationType(), change.changedFieldsJson(), change.reasonText(),
                        change.occurredAt(), change.operatorId()))
                .toList();
    }

    static String requireText(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_" + label.toUpperCase(), label + "不能为空");
        }
        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "FIELD_TOO_LONG", label + "长度不能超过 " + maxLength);
        }
        if (CONTROL_CHARS.matcher(stripped).find()) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "INVALID_CHARACTERS", label + "不能包含换行或控制字符");
        }
        return stripped;
    }

    static String validatePhone(String phone) {
        String stripped = requireText(phone, "联系电话", MAX_PHONE_LENGTH);
        if (stripped.length() < MIN_PHONE_LENGTH) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "INVALID_PHONE", "联系电话长度不能少于 " + MIN_PHONE_LENGTH + " 位");
        }
        if (!PHONE_ALLOWED.matcher(stripped).matches()) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "INVALID_PHONE", "联系电话只能包含数字、加号、括号、空格和短横线");
        }
        long digits = stripped.chars().filter(Character::isDigit).count();
        if (digits < 6) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "INVALID_PHONE", "联系电话至少需要 6 位数字");
        }
        return stripped;
    }

    /** 变化字段的 JSON 对象：列类型为 JSON，不能用 {@code Map.toString()} 伪 JSON。 */
    private static String changedFieldsJson(CustomerRecord before, CustomerRecord after) {
        java.util.LinkedHashMap<String, String> changed = new java.util.LinkedHashMap<>();
        if (!before.name().equals(after.name())) {
            changed.put("name", after.name());
        }
        if (!before.contactName().equals(after.contactName())) {
            changed.put("contactName", after.contactName());
        }
        if (!before.phone().equals(after.phone())) {
            changed.put("phone", after.phone());
        }
        if (!before.defaultAddress().equals(after.defaultAddress())) {
            changed.put("defaultAddress", after.defaultAddress());
        }
        if (changed.isEmpty()) {
            return null;
        }
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (var entry : changed.entrySet()) {
            if (!first) {
                json.append(',');
            }
            json.append('"').append(entry.getKey()).append("\":\"")
                    .append(entry.getValue().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
            first = false;
        }
        return json.append('}').toString();
    }

    private static CustomerRef toRef(CustomerRecord record) {
        return new CustomerRef(
                record.customerBusinessId(), record.customerNo(), record.status(),
                record.contentVersion(), record.rowVersion());
    }

    private static CustomerView toView(CustomerRecord record) {
        return new CustomerView(
                record.customerBusinessId(), record.customerNo(), record.name(), record.contactName(),
                record.phone(), record.defaultAddress(), record.status(), record.contentVersion(),
                record.rowVersion(), record.updatedAt());
    }

    /** 供快照服务复用：读取客户当前记录（不做权限校验，调用方已校验）。 */
    public CustomerRecord loadForSnapshot(String customerBusinessId) {
        return customers.findByBusinessId(customerBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "CUSTOMER_NOT_FOUND", "客户不存在"));
    }

    /** 供快照服务判断覆盖值是否为空。 */
    public static boolean noOverride(DeliveryContactOverride override) {
        return override == null || override.isEmpty();
    }
}
