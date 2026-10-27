package com.yumi.finance.cash.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 现金流水与业务日期汇总查询。 */
public interface CashQuery {

    CashPeriodSummary getPeriodSummary(LocalDate startDate, LocalDate endDate);

    List<CashFactView> search(LocalDate startDate, LocalDate endDate, String cashType);

    SourceCashSummary getSourceSummary(
            String sourceModule, String sourceType, String sourceBusinessId);

    /** 单笔现金事实与凭证文件标识，供订单/工资/成本页面查看。 */
    CashFactDetailView getFact(String cashFactBusinessId);

    record CashPeriodSummary(
            LocalDate startDate, LocalDate endDate, BigDecimal income, BigDecimal outflow,
            BigDecimal netAmount) {
    }

    record CashFactView(
            String cashFactBusinessId, String cashNo, String direction, String cashType,
            LocalDate businessDate, BigDecimal amount, boolean valid) {
    }

    record SourceCashSummary(
            String sourceModule, String sourceType, String sourceBusinessId,
            BigDecimal receivedAmount, BigDecimal paidAmount, BigDecimal refundedAmount) {
    }

    record CashFactDetailView(
            String cashFactBusinessId, String cashNo, String direction, String cashType,
            LocalDate businessDate, BigDecimal amount, String currencyCode, boolean valid,
            String reversesFactBusinessId, List<String> voucherFileRefs) {

        public CashFactDetailView {
            voucherFileRefs = List.copyOf(voucherFileRefs);
        }
    }
}
