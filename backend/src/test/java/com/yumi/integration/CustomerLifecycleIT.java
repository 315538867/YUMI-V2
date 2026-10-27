package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.customer.api.ChangeCustomerStatusCommand;
import com.yumi.customer.api.CreateCustomerCommand;
import com.yumi.customer.api.CustomerApplicationService;
import com.yumi.customer.api.CustomerQuery;
import com.yumi.customer.api.CustomerRef;
import com.yumi.customer.api.CustomerStatus;
import com.yumi.customer.api.CustomerView;
import com.yumi.customer.api.UpdateCustomerCommand;
import com.yumi.customer.application.CustomerService;
import com.yumi.customer.infrastructure.JdbcCustomerRepository;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.value.StableBusinessId;
import com.yumi.support.AbstractMySqlIntegrationTest;
import com.yumi.support.RecordingAuthorization;
import com.yumi.support.RecordingIdentity;
import com.yumi.support.RecordingNumbering;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 客户资料、启停与行版本校验（CU-01、CU-03）。
 *
 * <p>规则来源：`docs/一期/08-客户模块.md` §5、§9、§10、§11。使用真实 MySQL 验证：
 * 停用不破坏历史、并发编辑按行版本拒绝、停用客户不能用于新订单业务（以快照取得拒绝表达）。
 *
 * <p>权限与编号使用显式测试替身，只验证调用点与格式；真实平台认证在 2.11 验证。
 */
class CustomerLifecycleIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private CustomerApplicationService customers;

    private CustomerQuery customerQuery;

    private RecordingAuthorization authorization;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM customer_snapshots").update();
        jdbc.sql("DELETE FROM customer_change_facts").update();
        jdbc.sql("DELETE FROM customer_customers").update();

        transactionTemplate = new TransactionTemplate(transactionManager);
        authorization = new RecordingAuthorization();
        var repository = new JdbcCustomerRepository(jdbc);
        var numbering = new RecordingNumbering("C-");
        var identity = new RecordingIdentity("ACC-CUSTOMER-TEST");

        var service = new CustomerService(
                repository,
                numbering,
                authorization,
                identity,
                Clock.systemUTC(),
                transactionManager);
        customers = service;
        customerQuery = service;
    }

    @Test
    @DisplayName("创建客户生成只读编号、启用状态与初始版本")
    void createCustomerAssignsNumberOfVersions() {
        CustomerRef created = createCustomer("杭州示例工坊");

        assertThat(created.customerNo()).startsWith("C-");
        assertThat(created.status()).isEqualTo(CustomerStatus.ACTIVE);
        assertThat(created.contentVersion()).isEqualTo(1);
        assertThat(created.rowVersion()).isEqualTo(1);

        CustomerView view = customerQuery.getCurrent(created.customerBusinessId());
        assertThat(view.name()).isEqualTo("杭州示例工坊");
        assertThat(view.contactName()).isEqualTo("张三");
        assertThat(view.defaultAddress()).contains("文三路");
        assertThat(authorization.requiredPermissions()).contains(PermissionCodes.CUSTOMER_MANAGE);
    }

    @Test
    @DisplayName("修改资料递增内容版本与行版本，并记录变化事实")
    void updateIncrementsVersionsAndRecordsChange() {
        CustomerRef created = createCustomer("杭州示例工坊");

        CustomerRef updated = customers.update(new UpdateCustomerCommand(
                created.customerBusinessId(), "杭州示例工坊（新）", "李四", "13800000001",
                "杭州市西湖区文三路 2 号", created.rowVersion()));

        assertThat(updated.contentVersion()).isEqualTo(2);
        assertThat(updated.rowVersion()).isEqualTo(2);
        assertThat(customerQuery.getCurrent(created.customerBusinessId()).name())
                .isEqualTo("杭州示例工坊（新）");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer_change_facts WHERE customer_business_id = :id")
                .param("id", created.customerBusinessId())
                .query(Long.class)
                .single()).isEqualTo(2);
    }

    @Test
    @DisplayName("并发编辑：使用过期行版本返回并发冲突，不静默覆盖")
    void staleRowVersionIsRejected() {
        CustomerRef created = createCustomer("杭州示例工坊");
        customers.update(new UpdateCustomerCommand(
                created.customerBusinessId(), "第一次修改", "张三", "13800000000", "地址一",
                created.rowVersion()));

        assertThatThrownBy(() -> customers.update(new UpdateCustomerCommand(
                created.customerBusinessId(), "第二次修改", "张三", "13800000000", "地址二",
                created.rowVersion())))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).type())
                        .isEqualTo(ApiErrorType.CONCURRENT_CONFLICT));

        assertThat(customerQuery.getCurrent(created.customerBusinessId()).name()).isEqualTo("第一次修改");
    }

    @Test
    @DisplayName("停用不破坏历史：客户仍可读取，快照取得被拒绝")
    void deactivationPreservesHistoryButBlocksNewReferences() {
        CustomerRef created = createCustomer("杭州示例工坊");
        CustomerRef deactivated = customers.changeStatus(new ChangeCustomerStatusCommand(
                created.customerBusinessId(), CustomerStatus.INACTIVE, created.rowVersion()));

        assertThat(deactivated.status()).isEqualTo(CustomerStatus.INACTIVE);
        CustomerView view = customerQuery.getCurrent(created.customerBusinessId());
        assertThat(view.status()).isEqualTo(CustomerStatus.INACTIVE);
        assertThat(view.name()).isEqualTo("杭州示例工坊");
        // 状态属于客户业务内容，停用同样递增内容版本并记录变化事实（基线 04 §5）
        assertThat(view.contentVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("停用后重新启用可恢复，编号不变")
    void reactivationKeepsNumber() {
        CustomerRef created = createCustomer("杭州示例工坊");
        customers.changeStatus(new ChangeCustomerStatusCommand(
                created.customerBusinessId(), CustomerStatus.INACTIVE, created.rowVersion()));
        CustomerView inactive = customerQuery.getCurrent(created.customerBusinessId());

        CustomerRef reactivated = customers.changeStatus(new ChangeCustomerStatusCommand(
                created.customerBusinessId(), CustomerStatus.ACTIVE, inactive.rowVersion()));

        assertThat(reactivated.customerNo()).isEqualTo(created.customerNo());
        assertThat(reactivated.status()).isEqualTo(CustomerStatus.ACTIVE);
    }

    @Test
    @DisplayName("不扩展为 CRM：客户资料不含分级、授信、跟进字段")
    void noCrmFields() {
        java.util.Set<String> columns = new java.util.HashSet<>(jdbc.sql("""
                        SELECT COLUMN_NAME FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer_customers'
                        """).query(String.class).list());

        assertThat(columns).doesNotContain(
                "credit_limit", "customer_level", "grade", "sales_owner", "follow_up_status", "member_level");
        assertThat(columns).contains("customer_no", "name", "contact_name", "phone", "default_address",
                "status", "content_version", "row_version");
    }

    @Test
    @DisplayName("字段校验：名称与联系人必填且不含控制字符，电话至少 6 位数字")
    void fieldValidation() {
        assertThatThrownBy(() -> customers.create(new CreateCustomerCommand(
                "", "张三", "13800000000", "地址")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("客户名称");

        assertThatThrownBy(() -> customers.create(new CreateCustomerCommand(
                "客户", "张\n三", "13800000000", "地址")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("联系人");

        assertThatThrownBy(() -> customers.create(new CreateCustomerCommand(
                "客户", "张三", "12345", "地址")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("电话");

        assertThatThrownBy(() -> customers.create(new CreateCustomerCommand(
                "客户", "张三", "13800000000", "")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("地址");
    }

    @Test
    @DisplayName("搜索按关键字与状态过滤，分页稳定")
    void searchFiltersAndPaginates() {
        createCustomer("杭州示例工坊");
        createCustomer("宁波示例工坊");
        CustomerRef inactive = createCustomer("温州旧客户");
        customers.changeStatus(new ChangeCustomerStatusCommand(
                inactive.customerBusinessId(), CustomerStatus.INACTIVE, inactive.rowVersion()));

        List<CustomerView> active = customerQuery.search("示例", CustomerStatus.ACTIVE, 1, 10);
        assertThat(active).hasSize(2);
        assertThat(active).allMatch(view -> view.status() == CustomerStatus.ACTIVE);
        assertThat(customerQuery.countActive()).isEqualTo(2);

        List<CustomerView> firstPage = customerQuery.search(null, null, 1, 2);
        List<CustomerView> secondPage = customerQuery.search(null, null, 2, 2);
        assertThat(firstPage).hasSize(2);
        assertThat(secondPage).hasSize(1);
        assertThat(firstPage).doesNotContainAnyElementsOf(secondPage);
    }

    @Test
    @DisplayName("停用客户不能取得新快照，错误码明确指出客户已停用")
    void inactiveCustomerCannotBeReferenced() {
        CustomerRef created = createCustomer("杭州示例工坊");
        customers.changeStatus(new ChangeCustomerStatusCommand(
                created.customerBusinessId(), CustomerStatus.INACTIVE, created.rowVersion()));

        var snapshotService = customerSnapshotService();
        assertThatThrownBy(() -> snapshotService.acquireCurrentCustomerSnapshot(
                created.customerBusinessId(), com.yumi.customer.api.DeliveryContactOverride.NONE))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("CUSTOMER_INACTIVE"));
    }

    private CustomerApplicationService customerSnapshotReader() {
        return customers;
    }

    private com.yumi.customer.api.CustomerSnapshotService customerSnapshotService() {
        return new com.yumi.customer.application.CustomerSnapshotServiceImpl(
                new JdbcCustomerRepository(jdbc),
                new com.yumi.customer.infrastructure.JdbcCustomerSnapshotRepository(jdbc),
                authorization,
                new RecordingIdentity("ACC-CUSTOMER-TEST"),
                Clock.systemUTC());
    }

    private CustomerRef createCustomer(String name) {
        return customers.create(new CreateCustomerCommand(
                name, "张三", "13800000000", "杭州市西湖区文三路 1 号"));
    }
}
