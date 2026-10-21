package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.finance.cash.api.CashApplicationService;
import com.yumi.finance.cash.api.ReceiveOrderPaymentCommand;
import com.yumi.finance.cash.api.RefundOrderPaymentCommand;
import com.yumi.orders.api.ChangeConfirmedOrderCommand;
import com.yumi.orders.api.OrderChangeAction;
import com.yumi.orders.api.OrderChangeKind;
import com.yumi.orders.api.OrderChangeService;
import com.yumi.orders.api.OrderRef;
import com.yumi.platform.api.file.CreateUploadCommand;
import com.yumi.platform.api.file.FileApplicationService;
import com.yumi.platform.api.file.FileQuery;
import com.yumi.platform.api.file.FileStatus;
import com.yumi.platform.api.file.UploadRef;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.support.AbstractOrderFixtureTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 现金可选凭证：正式化与引用同事务，错误文件回滚，原付款与退款凭证分开。
 *
 * <p>规则来源：`docs/一期/13-财务结算模块.md` §7.3。真实 MySQL 验证：
 * 空附件可提交，错误引用不留下现金，凭证归本条现金事实。
 */
class CashVoucherIT extends AbstractOrderFixtureTest {

    @Autowired
    private CashApplicationService cash;

    @Autowired
    private FileApplicationService files;

    @Autowired
    private FileQuery fileQuery;

    @Autowired
    private OrderChangeService orderChanges;

    @Test
    @DisplayName("付款与退款各自携带可选凭证，错误引用整笔回滚")
    void vouchersBelongToEachCashFact() {
        OrderRef order = confirmOrderForMaking(2);
        UploadRef receiptFile = upload("receipt.png");
        UploadRef refundFile = upload("refund.png");

        var receipt = asAdmin(() -> cash.receiveOrder(new ReceiveOrderPaymentCommand(
                uniqueKey("CASH-RECEIPT"), order.orderBusinessId(), money("100.00"),
                LocalDate.parse("2026-10-20"), List.of(receiptFile.fileBusinessId().value()))));
        assertThat(fileQuery.getFile(receiptFile.fileBusinessId().value()).status())
                .isEqualTo(FileStatus.FORMAL);
        assertThat(vouchersOf(receipt.cashFactBusinessId())).containsExactly("CASH_VOUCHER");
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM platform_file_references
                        WHERE owner_business_id = :id AND purpose_code = 'CASH_VOUCHER'
                        """).param("id", receipt.cashFactBusinessId()).query(Long.class).single())
                .isEqualTo(1);

        // 减价形成退款义务，退款使用另一份凭证
        long contentVersion = orderJdbc.sql("""
                        SELECT content_version FROM orders_orders WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(Long.class).single();
        String itemId = orderJdbc.sql("""
                        SELECT order_item_business_id FROM orders_items WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(String.class).single();
        asAdmin(() -> orderChanges.change(new ChangeConfirmedOrderCommand(
                uniqueKey("ORDER-CHANGE"), order.orderBusinessId(), contentVersion,
                List.of(new OrderChangeAction(
                        OrderChangeKind.PRICE_OR_RECIPIENT, itemId, null,
                        SettlementMoney.of(new BigDecimal("40.00"), CurrencyCode.CNY),
                        null, null, null, null, null)),
                "客户议价")));
        var refund = asAdmin(() -> cash.refundOrder(new RefundOrderPaymentCommand(
                uniqueKey("CASH-REFUND"), order.orderBusinessId(), money("20.00"),
                LocalDate.parse("2026-10-20"), List.of(refundFile.fileBusinessId().value()))));
        assertThat(vouchersOf(receipt.cashFactBusinessId())).containsExactly("CASH_VOUCHER");
        assertThat(vouchersOf(refund.cashFactBusinessId())).containsExactly("CASH_VOUCHER");
        assertThat(voucherFiles(receipt.cashFactBusinessId()))
                .containsExactly(receiptFile.fileBusinessId().value());
        assertThat(voucherFiles(refund.cashFactBusinessId()))
                .containsExactly(refundFile.fileBusinessId().value());

        // 错误文件引用：现金事实、凭证关系全部回滚
        long cashFactsBefore = count("finance_cash_facts");
        assertThatThrownBy(() -> asAdmin(() -> cash.receiveOrder(
                new ReceiveOrderPaymentCommand(
                        uniqueKey("CASH-BAD"), order.orderBusinessId(), money("10.00"),
                        LocalDate.parse("2026-10-20"), List.of("FILE-NOT-EXIST")))))
                .isInstanceOf(ApiException.class);
        assertThat(count("finance_cash_facts")).isEqualTo(cashFactsBefore);
        assertThat(count("finance_cash_voucher_links")).isEqualTo(2);

        // 空附件合法
        OrderRef noVoucherOrder = confirmOrderForMaking(1);
        var noVoucher = asAdmin(() -> cash.receiveOrder(new ReceiveOrderPaymentCommand(
                uniqueKey("CASH-VOID"), noVoucherOrder.orderBusinessId(), money("10.00"),
                LocalDate.parse("2026-10-20"))));
        assertThat(vouchersOf(noVoucher.cashFactBusinessId())).isEmpty();
    }

    private UploadRef upload(String name) {
        return asAdmin(() -> files.createUpload(new CreateUploadCommand(
                name, "image/png",
                ("fake-png-content-" + java.util.UUID.randomUUID())
                        .getBytes(StandardCharsets.UTF_8))));
    }

    private List<String> vouchersOf(String cashFactId) {
        return orderJdbc.sql("""
                        SELECT purpose_code FROM finance_cash_voucher_links
                        WHERE cash_fact_business_id = :id
                        """).param("id", cashFactId).query(String.class).list();
    }

    private List<String> voucherFiles(String cashFactId) {
        return orderJdbc.sql("""
                        SELECT file_business_id FROM finance_cash_voucher_links
                        WHERE cash_fact_business_id = :id
                        """).param("id", cashFactId).query(String.class).list();
    }

    private static SettlementMoney money(String amount) {
        return SettlementMoney.of(new BigDecimal(amount), CurrencyCode.CNY);
    }

    private long count(String table) {
        return orderJdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
