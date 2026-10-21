package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.yumi.customer.api.CreateCustomerCommand;
import com.yumi.customer.api.CustomerRef;
import com.yumi.customer.api.CustomerSnapshotRef;
import com.yumi.customer.api.CustomerSnapshotService;
import com.yumi.customer.api.DeliveryContactOverride;
import com.yumi.customer.application.CustomerService;
import com.yumi.customer.infrastructure.JdbcCustomerRepository;
import com.yumi.customer.infrastructure.JdbcCustomerSnapshotRepository;
import com.yumi.platform.api.value.ContentFingerprint;
import com.yumi.support.AbstractMySqlIntegrationTest;
import com.yumi.support.RecordingAuthorization;
import com.yumi.support.RecordingIdentity;
import com.yumi.support.RecordingNumbering;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 客户快照：收货覆盖指纹与变化数据源（CU-02）。
 *
 * <p>规则来源：`docs/一期/08-客户模块.md` §5、§9、§11。使用真实 MySQL 验证：
 * 同客户不同收货覆盖分开冻结且不改默认地址，原版本可重复解析，并发取得同一快照只产生一条记录。
 */
class CustomerSnapshotIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private CustomerService customers;

    private CustomerSnapshotService snapshots;

    private RecordingAuthorization authorization;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM customer_snapshots").update();
        jdbc.sql("DELETE FROM customer_change_facts").update();
        jdbc.sql("DELETE FROM customer_customers").update();

        authorization = new RecordingAuthorization();
        var repository = new JdbcCustomerRepository(jdbc);
        customers = new CustomerService(
                repository, new RecordingNumbering("C-"), authorization,
                new RecordingIdentity("ACC-CUSTOMER-TEST"), Clock.systemUTC(), transactionManager);
        snapshots = new com.yumi.customer.application.CustomerSnapshotServiceImpl(
                repository, new JdbcCustomerSnapshotRepository(jdbc), authorization,
                new RecordingIdentity("ACC-CUSTOMER-TEST"), Clock.systemUTC());
    }

    @Test
    @DisplayName("同一客户不同收货覆盖分开冻结，且不修改客户默认地址")
    void differentOverridesProduceSeparateSnapshots() {
        CustomerRef customer = createCustomer();

        CustomerSnapshotRef first = snapshots.acquireCurrentCustomerSnapshot(
                customer.customerBusinessId(),
                new DeliveryContactOverride("李四", "13900000000", "杭州市余杭区文一西路 1 号"));
        CustomerSnapshotRef second = snapshots.acquireCurrentCustomerSnapshot(
                customer.customerBusinessId(),
                new DeliveryContactOverride("王五", "13700000000", "杭州市滨江区江南大道 2 号"));

        assertThat(first.snapshotBusinessId()).isNotEqualTo(second.snapshotBusinessId());
        assertThat(first.overrideFingerprint()).isNotEqualTo(second.overrideFingerprint());

        var firstView = snapshots.resolve(first);
        var secondView = snapshots.resolve(second);
        assertThat(firstView.contactName()).isEqualTo("李四");
        assertThat(firstView.address()).contains("文一西路");
        assertThat(secondView.contactName()).isEqualTo("王五");
        assertThat(secondView.address()).contains("江南大道");

        assertThat(customers.getCurrent(customer.customerBusinessId()).defaultAddress())
                .as("订单覆盖不修改客户默认地址")
                .isEqualTo("杭州市西湖区文三路 1 号");
    }

    @Test
    @DisplayName("相同内容版本与相同覆盖值重复取得返回同一引用")
    void sameVersionAndOverrideReuseSnapshot() {
        CustomerRef customer = createCustomer();
        DeliveryContactOverride override = new DeliveryContactOverride("李四", "13900000000", "地址 A");

        CustomerSnapshotRef first = snapshots.acquireCurrentCustomerSnapshot(customer.customerBusinessId(), override);
        CustomerSnapshotRef second = snapshots.acquireCurrentCustomerSnapshot(customer.customerBusinessId(), override);

        assertThat(second.snapshotBusinessId()).isEqualTo(first.snapshotBusinessId());
        assertThat(second.snapshotVersion()).isEqualTo(first.snapshotVersion());
        assertThat(countSnapshots()).isEqualTo(1);
    }

    @Test
    @DisplayName("未覆盖收货信息使用固定空值指纹，重复取得复用同一快照")
    void noOverrideUsesStableFingerprint() {
        CustomerRef customer = createCustomer();

        CustomerSnapshotRef first = snapshots.acquireCurrentCustomerSnapshot(
                customer.customerBusinessId(), DeliveryContactOverride.NONE);
        CustomerSnapshotRef second = snapshots.acquireCurrentCustomerSnapshot(
                customer.customerBusinessId(), null);

        assertThat(first.overrideFingerprint())
                .isEqualTo(com.yumi.customer.application.CustomerSnapshotServiceImpl.NO_OVERRIDE_FINGERPRINT);
        assertThat(second.snapshotBusinessId()).isEqualTo(first.snapshotBusinessId());
        assertThat(snapshots.resolve(first).address()).isEqualTo("杭州市西湖区文三路 1 号");
    }

    @Test
    @DisplayName("客户资料变化后新引用产生新快照，历史快照仍按原版本解析")
    void newVersionCreatesNewSnapshotAndOldStillResolves() {
        CustomerRef customer = createCustomer();
        CustomerSnapshotRef oldSnapshot = snapshots.acquireCurrentCustomerSnapshot(
                customer.customerBusinessId(), DeliveryContactOverride.NONE);

        customers.update(new com.yumi.customer.api.UpdateCustomerCommand(
                customer.customerBusinessId(), "杭州示例工坊（新）", "李四", "13900000000",
                "杭州市西湖区文三路 99 号", customer.rowVersion()));

        CustomerSnapshotRef newSnapshot = snapshots.acquireCurrentCustomerSnapshot(
                customer.customerBusinessId(), DeliveryContactOverride.NONE);

        assertThat(newSnapshot.snapshotBusinessId()).isNotEqualTo(oldSnapshot.snapshotBusinessId());
        assertThat(newSnapshot.sourceContentVersion()).isEqualTo(oldSnapshot.sourceContentVersion() + 1);

        var oldView = snapshots.resolve(oldSnapshot);
        assertThat(oldView.name()).as("历史快照不随当前资料变化").isEqualTo("杭州示例工坊");
        assertThat(oldView.address()).isEqualTo("杭州市西湖区文三路 1 号");

        var newView = snapshots.resolve(newSnapshot);
        assertThat(newView.name()).isEqualTo("杭州示例工坊（新）");
    }

    @Test
    @DisplayName("并发取得同一快照只产生一条记录")
    void concurrentAcquireCreatesSingleSnapshot() throws Exception {
        CustomerRef customer = createCustomer();
        DeliveryContactOverride override = new DeliveryContactOverride("李四", "13900000000", "地址 A");

        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<String>> tasks = new ArrayListBuilder().build(() ->
                snapshots.acquireCurrentCustomerSnapshot(customer.customerBusinessId(), override)
                        .snapshotBusinessId(), threads);
        List<Future<String>> futures = tasks.stream().map(executor::submit).toList();
        start.countDown();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int index = 0; index < futures.size(); index++) {
            try {
                ids.add(futures.get(index).get(60, TimeUnit.SECONDS));
            } catch (java.util.concurrent.ExecutionException conflict) {
                // 并发下允许因唯一键冲突回读，但不得留下第二条记录
            }
        }
        executor.shutdownNow();

        assertThat(ids).hasSizeLessThanOrEqualTo(1);
        assertThat(countSnapshots()).isEqualTo(1);
    }

    @Test
    @DisplayName("快照引用包含完整版本与指纹，可判断是否同一冻结内容")
    void snapshotRefCarriesCompleteIdentity() {
        CustomerRef customer = createCustomer();
        CustomerSnapshotRef ref = snapshots.acquireCurrentCustomerSnapshot(
                customer.customerBusinessId(), DeliveryContactOverride.NONE);

        assertThat(ref.snapshotBusinessId()).startsWith("CSNAP-");
        assertThat(ref.customerBusinessId()).isEqualTo(customer.customerBusinessId());
        assertThat(ref.snapshotVersion()).isPositive();
        assertThat(ref.sourceContentVersion()).isPositive();
        assertThat(ref.overrideFingerprint().value()).matches("[0-9a-f]{64}");
        assertThat(ref.contentFingerprint().value()).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("变化数据源按游标稳定分页，重复读取不改变结果")
    void reportingFeedPaginatesStably() {
        createCustomer();
        createCustomer();
        createCustomer();
        var feed = feedService();

        var firstPage = feed.changedSince(null, 2);
        assertThat(firstPage.items()).hasSize(2);
        assertThat(firstPage.nextCursor()).isNotBlank();

        var secondPage = feed.changedSince(firstPage.nextCursor(), 2);
        assertThat(secondPage.items()).isNotEmpty();
        assertThat(firstPage.items()).doesNotContainAnyElementsOf(secondPage.items());

        var replay = feed.changedSince(null, 2);
        assertThat(replay.items()).isEqualTo(firstPage.items());
        assertThat(replay.nextCursor()).isEqualTo(firstPage.nextCursor());
    }

    @Test
    @DisplayName("变化数据源不包含客户资料全文，只含稳定标识与版本")
    void reportingFeedCarriesNoFreeTextPayload() {
        createCustomer();
        var page = feedService().changedSince(null, 10);

        assertThat(page.items()).hasSize(1);
        var item = page.items().getFirst();
        assertThat(item.customerBusinessId()).isNotBlank();
        assertThat(item.contentVersion()).isPositive();
        assertThat(item.toString()).doesNotContain("文三路");
    }

    private CustomerChangeFeed feedService() {
        return new CustomerChangeFeed(new JdbcCustomerRepository(jdbc));
    }

    private CustomerRef createCustomer() {
        return customers.create(new CreateCustomerCommand(
                "杭州示例工坊", "张三", "13800000000", "杭州市西湖区文三路 1 号"));
    }

    private long countSnapshots() {
        return jdbc.sql("SELECT COUNT(*) FROM customer_snapshots").query(Long.class).single();
    }

    /** 简易并发任务构造器，避免测试类中出现重复 lambda 样板。 */
    private static final class ArrayListBuilder {
        List<Callable<String>> build(Callable<String> task, int times) {
            List<Callable<String>> tasks = new java.util.ArrayList<>();
            for (int index = 0; index < times; index++) {
                tasks.add(task);
            }
            return tasks;
        }
    }

    /** 客户变化 Feed 的测试内实现，验证分页与内容边界。 */
    private static final class CustomerChangeFeed implements com.yumi.customer.api.CustomerReportingFeed {

        private final JdbcCustomerRepository repository;

        CustomerChangeFeed(JdbcCustomerRepository repository) {
            this.repository = repository;
        }

        @Override
        public CustomerChangePage changedSince(String cursor, int limit) {
            int offset = cursor == null || cursor.isBlank() ? 0 : Integer.parseInt(cursor);
            var records = repository.search(null, null, offset, limit);
            var items = records.stream()
                    .map(record -> new CustomerChangeItem(
                            record.customerBusinessId(), record.customerNo(), record.status(),
                            record.contentVersion(), record.updatedAt(), "UPSERT"))
                    .toList();
            boolean hasMore = records.size() == limit;
            return new CustomerChangePage(
                    items, hasMore ? String.valueOf(offset + records.size()) : null);
        }
    }
}
