package com.yumi.catalog.customer;

import com.yumi.catalog.changelog.MasterDataChangeLogService;
import com.yumi.catalog.customer.CustomerDtos.CreateCustomerRequest;
import com.yumi.catalog.customer.CustomerDtos.CreateOutcome;
import com.yumi.catalog.customer.CustomerDtos.CustomerDetail;
import com.yumi.catalog.customer.CustomerDtos.CustomerPatchRequest;
import com.yumi.catalog.customer.CustomerDtos.CustomerView;
import com.yumi.catalog.customer.CustomerDtos.DuplicateCandidate;
import com.yumi.catalog.customer.internal.CustomerEntity;
import com.yumi.catalog.customer.internal.CustomerOrderSummaryReference;
import com.yumi.catalog.customer.internal.CustomerRepository;
import com.yumi.calculation.DecimalPolicy;
import com.yumi.identity.AuditContext;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import com.yumi.shared.numbering.SequenceAllocator;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 客户服务（任务 2.5）：
 * - 重复检测按 name 精确相等 OR phone 精确相等（phone 空则不按电话比），最多 5 条候选；
 * - 有候选且未 duplicateConfirmed → 只提示不入库（提示本身由写审计过滤器留痕）；
 * - 无候选或已确认 → 分配 C 编号独立入库，确认也绝不合并；
 * - 编辑带 version 乐观锁（过期 409 CONFLICT_VERSION）并追加变更日志；
 * - 客户无删除接口、无自动合并。
 */
@Service
@Transactional
public class CustomerService {

    public static final int DUPLICATE_CANDIDATE_LIMIT = 5;

    private final CustomerRepository customerRepository;
    private final SequenceAllocator sequenceAllocator;
    private final AuditContext auditContext;
    private final MasterDataChangeLogService changeLogService;
    private final CustomerOrderSummaryReference summaryReference;

    public CustomerService(CustomerRepository customerRepository, SequenceAllocator sequenceAllocator,
                           AuditContext auditContext, MasterDataChangeLogService changeLogService,
                           CustomerOrderSummaryReference summaryReference) {
        this.customerRepository = customerRepository;
        this.sequenceAllocator = sequenceAllocator;
        this.summaryReference = summaryReference;
        this.auditContext = auditContext;
        this.changeLogService = changeLogService;
    }

    public CreateOutcome create(CreateCustomerRequest request) {
        var name = request.name().trim();
        var phone = blankToNull(request.phone() == null ? null : request.phone().trim());

        var candidates = findDuplicates(name, phone);
        if (!candidates.isEmpty() && !Boolean.TRUE.equals(request.duplicateConfirmed())) {
            return new CreateOutcome(false, candidates, null);
        }

        var entity = new CustomerEntity();
        entity.setCustomerNo(SequenceAllocator.format("C", sequenceAllocator.next("customers")));
        entity.setName(name);
        entity.setContact(blankToNull(request.contact()));
        entity.setPhone(phone);
        entity.setNote(blankToNull(request.note()));
        entity.setDefaultRecipient(request.defaultRecipient());
        entity.setDefaultRecipientPhone(request.defaultRecipientPhone());
        entity.setDefaultRegion(request.defaultRegion());
        entity.setDefaultAddress(request.defaultAddress());
        var saved = customerRepository.save(entity);
        return new CreateOutcome(true, List.of(), view(saved));
    }

    @Transactional(readOnly = true)
    public List<CustomerView> list(String name, String phone) {
        var filterName = blankToNull(name == null ? null : name.trim());
        var filterPhone = blankToNull(phone == null ? null : phone.trim());
        List<CustomerEntity> rows;
        if (filterName != null && filterPhone != null) {
            rows = customerRepository.findByNameOrderByIdAsc(filterName).stream()
                    .filter(candidate -> filterPhone.equals(candidate.getPhone()))
                    .toList();
        } else if (filterName != null) {
            rows = customerRepository.findByNameOrderByIdAsc(filterName);
        } else if (filterPhone != null) {
            rows = customerRepository.findByPhoneOrderByIdAsc(filterPhone);
        } else {
            rows = customerRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
        }
        return rows.stream().map(this::view).toList();
    }

    /** 客户详情 + 只读汇总：汇总实时由订单与收退款事实聚合（任务 2.6 的接线），不落客户余额列。 */
    @Transactional(readOnly = true)
    public CustomerDetail detail(long id) {
        var entity = requireCustomer(id);
        var summary = summaryReference.summary(id);
        return CustomerDetail.of(view(entity), new CustomerSummary(
                String.valueOf(summary.orderCount()),
                DecimalPolicy.money(summary.totalOrdered()).toPlainString(),
                DecimalPolicy.money(summary.totalReceived()).toPlainString(),
                DecimalPolicy.money(summary.totalRefunded()).toPlainString()));
    }

    public CustomerView patch(long id, CustomerPatchRequest request) {
        var entity = requireCustomer(id);
        if (!entity.getVersion().equals(request.version())) {
            throw new ApiException(ErrorCode.CONFLICT_VERSION);
        }

        requireNotBlankIfPresent("name", "客户名称不能为空", request.name());
        requireNotBlankIfPresent("defaultRecipient", "默认收件人不能为空", request.defaultRecipient());
        requireNotBlankIfPresent("defaultRecipientPhone", "默认收件人电话不能为空", request.defaultRecipientPhone());
        requireNotBlankIfPresent("defaultRegion", "默认地区不能为空", request.defaultRegion());
        requireNotBlankIfPresent("defaultAddress", "默认地址不能为空", request.defaultAddress());

        var before = view(entity);
        if (request.name() != null) {
            entity.setName(request.name().trim());
        }
        if (request.contact() != null) {
            entity.setContact(blankToNull(request.contact()));
        }
        if (request.phone() != null) {
            entity.setPhone(blankToNull(request.phone().trim()));
        }
        if (request.note() != null) {
            entity.setNote(blankToNull(request.note()));
        }
        if (request.defaultRecipient() != null) {
            entity.setDefaultRecipient(request.defaultRecipient());
        }
        if (request.defaultRecipientPhone() != null) {
            entity.setDefaultRecipientPhone(request.defaultRecipientPhone());
        }
        if (request.defaultRegion() != null) {
            entity.setDefaultRegion(request.defaultRegion());
        }
        if (request.defaultAddress() != null) {
            entity.setDefaultAddress(request.defaultAddress());
        }

        // 先落定 version/updated_at 再生成 after 快照与响应（并发冲突在此抛出 → 409）
        customerRepository.flush();
        var after = view(entity);

        var audit = auditContext.current();
        changeLogService.record("CUSTOMER", entity.getId(), entity.getCustomerNo(),
                before, after, request.reason(), audit.adminUsername(), audit.requestId());
        return after;
    }

    private List<DuplicateCandidate> findDuplicates(String name, String phone) {
        List<CustomerEntity> found = phone == null
                ? customerRepository.findTop5ByNameOrderByIdAsc(name)
                : customerRepository.findTop5ByNameOrPhoneOrderByIdAsc(name, phone);
        return found.stream().limit(DUPLICATE_CANDIDATE_LIMIT)
                .map(candidate -> new DuplicateCandidate(
                        candidate.getCustomerNo(), candidate.getName(), candidate.getPhone()))
                .toList();
    }

    private CustomerEntity requireCustomer(long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "客户不存在"));
    }

    private void requireNotBlankIfPresent(String field, String message, String value) {
        if (value != null && value.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, message,
                    List.of(new ApiFieldError(field, message)));
        }
    }

    private CustomerView view(CustomerEntity entity) {
        return new CustomerView(
                entity.getId(),
                entity.getCustomerNo(),
                entity.getName(),
                entity.getContact(),
                entity.getPhone(),
                entity.getNote(),
                entity.getDefaultRecipient(),
                entity.getDefaultRecipientPhone(),
                entity.getDefaultRegion(),
                entity.getDefaultAddress(),
                entity.getVersion(),
                entity.getCreatedAt().toString(),
                entity.getUpdatedAt().toString());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
