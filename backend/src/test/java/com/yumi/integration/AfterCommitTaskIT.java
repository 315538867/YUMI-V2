package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.aftercommit.api.AfterCommitTaskLease;
import com.yumi.platform.aftercommit.api.AfterCommitTaskStatus;
import com.yumi.platform.aftercommit.application.AfterCommitTaskService;
import com.yumi.platform.aftercommit.infrastructure.JdbcAfterCommitTaskRepository;
import com.yumi.platform.aftercommit.api.EnqueueAfterCommitTaskCommand;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.support.AbstractMySqlIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 提交后任务：租约竞争、退避与人工恢复（PL-04）。
 *
 * <p>规则来源：`docs/一期/06-平台公共能力模块.md` §5、§9、§12 与 `${PL-04}`：
 * 回滚事务无可执行任务；重试不影响原业务；租约只被一名执行者持有；失败可定位并可人工恢复。
 */
class AfterCommitTaskIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private MutableClock clock;

    private AfterCommitTaskService service;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-20T02:00:00Z"));
        transactionTemplate = new TransactionTemplate(transactionManager);
        jdbc.sql("DELETE FROM platform_after_commit_tasks").update();
        jdbc.sql("DELETE FROM platform_probe_business_facts").update();
        var repository = new JdbcAfterCommitTaskRepository(jdbc, new ObjectMapper());
        service = new AfterCommitTaskService(
                repository, new ObjectMapper(), clock, Duration.ofMinutes(2), 3);
    }

    @Test
    @DisplayName("业务事务回滚时没有可执行任务")
    void rolledBackTransactionLeavesNoTask() {
        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            service.enqueue(new EnqueueAfterCommitTaskCommand(
                    "reporting.refresh", "REPORT-2026-10", Map.of("period", "2026-10")));
            throw new IllegalStateException("模拟业务失败");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM platform_after_commit_tasks").query(Long.class).single())
                .isZero();
        assertThat(service.claimNext("worker-1")).isEmpty();
    }

    @Test
    @DisplayName("业务提交后任务可被领取，去重键避免重复登记")
    void committedTaskIsClaimableAndDeduplicated() {
        transactionTemplate.execute(status -> {
            service.enqueue(new EnqueueAfterCommitTaskCommand(
                    "reporting.refresh", "REPORT-2026-10", Map.of("period", "2026-10")));
            service.enqueue(new EnqueueAfterCommitTaskCommand(
                    "reporting.refresh", "REPORT-2026-10", Map.of("period", "2026-10")));
            return null;
        });

        assertThat(jdbc.sql("SELECT COUNT(*) FROM platform_after_commit_tasks").query(Long.class).single())
                .isEqualTo(1);
        assertThat(service.claimNext("worker-1")).isPresent();
    }

    @Test
    @DisplayName("多个执行者同时领取同一任务时只有一个获得租约")
    void onlyOneWorkerGetsLease() throws Exception {
        transactionTemplate.execute(status -> {
            service.enqueue(new EnqueueAfterCommitTaskCommand(
                    "reporting.refresh", "REPORT-2026-10", Map.of()));
            return null;
        });

        int workers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int index = 0; index < workers; index++) {
            String owner = "worker-" + index;
            tasks.add(() -> {
                start.await();
                return transactionTemplate.execute(status -> service.claimNext(owner).isPresent());
            });
        }
        List<Future<Boolean>> futures = tasks.stream().map(executor::submit).toList();
        start.countDown();
        int claimed = 0;
        for (Future<Boolean> future : futures) {
            if (Boolean.TRUE.equals(future.get(60, TimeUnit.SECONDS))) {
                claimed++;
            }
        }
        executor.shutdownNow();

        assertThat(claimed).isEqualTo(1);
    }

    @Test
    @DisplayName("非租约持有者不能提交结果")
    void nonLeaseOwnerCannotComplete() {
        transactionTemplate.execute(status -> {
            service.enqueue(new EnqueueAfterCommitTaskCommand("reporting.refresh", "K-1", Map.of()));
            return null;
        });
        AfterCommitTaskLease lease = transactionTemplate.execute(status -> service.claimNext("worker-1"))
                .orElseThrow();

        AfterCommitTaskLease stolen = new AfterCommitTaskLease(
                lease.taskBusinessId(), lease.taskType(), "worker-2", lease.leaseUntil(), lease.attempt());

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            service.complete(stolen);
            return null;
        })).isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("TASK_LEASE_CONFLICT"));
    }

    @Test
    @DisplayName("失败按退避重试，达到上限后进入 DEAD 且原业务仍有效")
    void failuresBackOffThenDeadWithoutRollingBackBusiness() {
        transactionTemplate.execute(status -> {
            jdbc.sql("""
                            INSERT INTO platform_probe_business_facts (fact_business_id, created_at)
                            VALUES ('FACT-1', UTC_TIMESTAMP(6))
                            """).update();
            service.enqueue(new EnqueueAfterCommitTaskCommand("reporting.refresh", "K-1", Map.of()));
            return null;
        });

        for (int attempt = 1; attempt <= 3; attempt++) {
            int currentAttempt = attempt;
            AfterCommitTaskLease lease = transactionTemplate.execute(status -> service.claimNext("worker-1"))
                    .orElseThrow(() -> new AssertionError("第 " + currentAttempt + " 次应能领取任务"));
            assertThat(lease.attempt()).isEqualTo(attempt);
            transactionTemplate.execute(status -> {
                service.fail(lease, "数据源暂时不可用");
                return null;
            });
            var view = service.view(lease.taskBusinessId());
            if (attempt < 3) {
                assertThat(view.status()).isEqualTo(AfterCommitTaskStatus.RETRY_WAIT);
                assertThat(view.lastError()).contains("数据源暂时不可用");
                clock.advance(Duration.ofMinutes(31));
            } else {
                assertThat(view.status()).isEqualTo(AfterCommitTaskStatus.DEAD);
            }
        }

        assertThat(jdbc.sql("SELECT COUNT(*) FROM platform_probe_business_facts").query(Long.class).single())
                .as("后台任务失败不回滚已提交业务")
                .isEqualTo(1);
        var claimAfterDead = transactionTemplate.execute(status -> service.claimNext("worker-1"));
        assertThat(claimAfterDead).isEmpty();
    }

    @Test
    @DisplayName("管理员重试沿用原任务身份，放弃保留原因且不改原业务")
    void administratorCanRetryOrAbandon() {
        String taskBusinessId = transactionTemplate.execute(status -> service.enqueue(
                new EnqueueAfterCommitTaskCommand("reporting.refresh", "K-1", Map.of())).taskBusinessId().value());

        AfterCommitTaskLease lease = transactionTemplate.execute(status -> service.claimNext("worker-1"))
                .orElseThrow();
        transactionTemplate.execute(status -> {
            service.fail(lease, "第一次失败");
            return null;
        });
        clock.advance(Duration.ofMinutes(31));

        AfterCommitTaskLease second = transactionTemplate.execute(status -> service.claimNext("worker-1"))
                .orElseThrow();
        transactionTemplate.execute(status -> {
            service.fail(second, "第二次失败");
            return null;
        });
        clock.advance(Duration.ofMinutes(31));

        AfterCommitTaskLease third = transactionTemplate.execute(status -> service.claimNext("worker-1"))
                .orElseThrow();
        transactionTemplate.execute(status -> {
            service.fail(third, "第三次失败");
            return null;
        });
        assertThat(service.view(taskBusinessId).status()).isEqualTo(AfterCommitTaskStatus.DEAD);

        transactionTemplate.execute(status -> {
            service.retry(taskBusinessId);
            return null;
        });
        var reopened = service.view(taskBusinessId);
        assertThat(reopened.status()).isEqualTo(AfterCommitTaskStatus.PENDING);
        assertThat(reopened.attempts()).isZero();
        assertThat(reopened.taskBusinessId()).isEqualTo(taskBusinessId);
        assertThat(reopened.deduplicationKey()).isEqualTo("K-1");

        transactionTemplate.execute(status -> {
            service.abandon(taskBusinessId, "改为人工处理");
            return null;
        });
        var abandoned = service.view(taskBusinessId);
        assertThat(abandoned.status()).isEqualTo(AfterCommitTaskStatus.DEAD);
        assertThat(abandoned.lastError()).isEqualTo("改为人工处理");

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            service.abandon(taskBusinessId, " ");
            return null;
        })).isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).type())
                        .isEqualTo(ApiErrorType.VALIDATION));
    }

    @Test
    @DisplayName("租约过期后任务可被其他执行者重新领取")
    void expiredLeaseIsReclaimable() {
        transactionTemplate.execute(status -> {
            service.enqueue(new EnqueueAfterCommitTaskCommand("reporting.refresh", "K-1", Map.of()));
            return null;
        });
        AfterCommitTaskLease first = transactionTemplate.execute(status -> service.claimNext("worker-1"))
                .orElseThrow();

        clock.advance(Duration.ofMinutes(5));

        AfterCommitTaskLease second = transactionTemplate.execute(status -> service.claimNext("worker-2"))
                .orElseThrow();
        assertThat(second.leaseOwner()).isEqualTo("worker-2");
        assertThat(second.taskBusinessId()).isEqualTo(first.taskBusinessId());

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            service.complete(first);
            return null;
        })).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("按状态可定位失败任务及其来源标识")
    void failedTasksAreQueryable() {
        String taskBusinessId = transactionTemplate.execute(status -> service.enqueue(
                new EnqueueAfterCommitTaskCommand(
                        "reporting.refresh", "REPORT-2026-10", Map.of("source", "orders.trade")))
                .taskBusinessId().value());
        AfterCommitTaskLease lease = transactionTemplate.execute(status -> service.claimNext("worker-1"))
                .orElseThrow();
        transactionTemplate.execute(status -> {
            service.fail(lease, "投影写入失败");
            return null;
        });

        var failed = service.listByStatus(AfterCommitTaskStatus.RETRY_WAIT, 10);
        assertThat(failed).hasSize(1);
        assertThat(failed.getFirst().taskBusinessId()).isEqualTo(taskBusinessId);
        assertThat(failed.getFirst().payload()).containsEntry("source", "orders.trade");
        assertThat(failed.getFirst().lastError()).contains("投影写入失败");
    }

    /** 可推进时钟，用于验证退避与租约过期，无需真实等待。 */
    static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
