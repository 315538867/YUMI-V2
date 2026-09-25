package com.yumi.catalog.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 客户域公开 DTO：创建/编辑请求（jakarta 校验走统一 400 VALIDATION_INVALID）、
 * 客户视图、重复提示与详情（含只读 summary）。
 */
public final class CustomerDtos {

    private CustomerDtos() {
    }

    public record CreateCustomerRequest(
            @NotBlank(message = "客户名称不能为空") @Size(max = 100) String name,
            @Size(max = 100) String contact,
            @Size(max = 30) String phone,
            @Size(max = 1000) String note,
            @NotBlank(message = "默认收件人不能为空") @Size(max = 100) String defaultRecipient,
            @NotBlank(message = "默认收件人电话不能为空") @Size(max = 30) String defaultRecipientPhone,
            @NotBlank(message = "默认地区不能为空") @Size(max = 200) String defaultRegion,
            @NotBlank(message = "默认地址不能为空") @Size(max = 500) String defaultAddress,
            Boolean duplicateConfirmed) {
    }

    /**
     * 编辑：version 必填做乐观锁；其余字段缺省/为 null 表示保持原值（reason 可选，见决策报告）。
     */
    public record CustomerPatchRequest(
            @NotNull(message = "version 不能为空") Long version,
            @Size(max = 100) String name,
            @Size(max = 100) String contact,
            @Size(max = 30) String phone,
            @Size(max = 1000) String note,
            @Size(max = 100) String defaultRecipient,
            @Size(max = 30) String defaultRecipientPhone,
            @Size(max = 200) String defaultRegion,
            @Size(max = 500) String defaultAddress,
            @Size(max = 500) String reason) {
    }

    public record CustomerView(
            long id,
            String customerNo,
            String name,
            String contact,
            String phone,
            String note,
            String defaultRecipient,
            String defaultRecipientPhone,
            String defaultRegion,
            String defaultAddress,
            long version,
            String createdAt,
            String updatedAt) {
    }

    /**
     * 详情 = 客户视图 + 只读汇总（任务 2.6，由订单与收退款事实实时聚合）；summary 之外不追加任何余额字段。
     */
    public record CustomerDetail(
            long id,
            String customerNo,
            String name,
            String contact,
            String phone,
            String note,
            String defaultRecipient,
            String defaultRecipientPhone,
            String defaultRegion,
            String defaultAddress,
            long version,
            String createdAt,
            String updatedAt,
            CustomerSummary summary) {

        public static CustomerDetail of(CustomerView view, CustomerSummary summary) {
            return new CustomerDetail(
                    view.id(), view.customerNo(), view.name(), view.contact(), view.phone(), view.note(),
                    view.defaultRecipient(), view.defaultRecipientPhone(), view.defaultRegion(),
                    view.defaultAddress(), view.version(), view.createdAt(), view.updatedAt(), summary);
        }
    }

    public record DuplicateCandidate(String customerNo, String name, String phone) {
    }

    /**
     * 重复提示体：HTTP 200 且 created=false，不入库（客户重复只能提示不能自动合并）。
     */
    public record DuplicatePrompt(boolean created, List<DuplicateCandidate> duplicateCandidates) {
    }

    /**
     * 创建结果：created=false → 仅带重复候选；created=true → 带新编号客户视图。
     */
    public record CreateOutcome(boolean created, List<DuplicateCandidate> duplicateCandidates, CustomerView customer) {
    }
}
