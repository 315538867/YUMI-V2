package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.reporting.api.IngestReportingSourcesCommand;
import com.yumi.reporting.api.RebuildReportingProjectionCommand;
import com.yumi.reporting.api.ReportingIngestionService;
import com.yumi.reporting.api.ReportingRebuildService;
import com.yumi.reporting.application.ReportingRebuildRepository;
import com.yumi.reporting.application.ReportingRebuildServiceImpl;
import com.yumi.support.AbstractOrderFixtureTest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 影子投影重建、来源对账与原子切换。
 *
 * <p>规则来源：`docs/一期/14-报表模块.md` §6、§8。真实 MySQL 验证：
 * 重建后对账为零并切换 ACTIVE；失败保留旧可用版本。
 */
class ReportingRebuildIT extends AbstractOrderFixtureTest {

    @Autowired
    private ReportingIngestionService ingestion;

    @Autowired
    private ReportingRebuildService rebuilds;

    @Autowired
    private ReportingRebuildRepository rebuildRepository;

    @Autowired
    private IdempotentCommandService idempotency;

    @Autowired
    private AuthorizationService authorization;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("从空影子重建合计一致并原子切换，旧版本保留为 RETIRED")
    void rebuildSwitchesActiveVersionAtomically() {
        completedOrder(2);
        asAdmin(() -> ingestion.ingest(
                new IngestReportingSourcesCommand(uniqueKey("REPORT-INGEST"))));

        var first = asAdmin(() -> rebuilds.rebuild(
                new RebuildReportingProjectionCommand(uniqueKey("REPORT-REBUILD"))));
        assertThat(first.activated()).isTrue();
        assertThat(first.recordCount()).isPositive();
        assertThat(first.differenceCount()).isZero();
        assertThat(asAdmin(() -> rebuilds.listDifferences(first.versionBusinessId()))).isEmpty();
        assertThat(asAdmin(() -> rebuilds.listVersions()))
                .singleElement()
                .satisfies(version -> {
                    assertThat(version.status()).isEqualTo("ACTIVE");
                    assertThat(version.recordCount()).isPositive();
                });

        var second = asAdmin(() -> rebuilds.rebuild(
                new RebuildReportingProjectionCommand(uniqueKey("REPORT-REBUILD"))));
        assertThat(second.activated()).isTrue();
        assertThat(second.recordCount()).isEqualTo(first.recordCount());
        assertThat(asAdmin(() -> rebuilds.listVersions()))
                .extracting(ReportingRebuildService.VersionView::status)
                .containsExactlyInAnyOrder("ACTIVE", "RETIRED");
        assertThat(orderJdbc.sql("""
                        SELECT version_business_id FROM reporting_projection_active
                        WHERE singleton_id = 1
                        """).query(String.class).single())
                .isEqualTo(second.versionBusinessId());
    }

    @Test
    @DisplayName("复制失败时回滚影子版本，旧 ACTIVE 版本保持可用")
    void failedRebuildKeepsOldProjection() {
        completedOrder(1);
        asAdmin(() -> ingestion.ingest(
                new IngestReportingSourcesCommand(uniqueKey("REPORT-INGEST"))));
        var active = asAdmin(() -> rebuilds.rebuild(
                new RebuildReportingProjectionCommand(uniqueKey("REPORT-REBUILD"))));

        var failing = failingRepository();
        var service = new ReportingRebuildServiceImpl(
                failing, idempotency, authorization, clock, transactionManager);
        assertThatThrownBy(() -> asAdmin(() -> service.rebuild(
                new RebuildReportingProjectionCommand(uniqueKey("REPORT-REBUILD")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("注入");

        assertThat(orderJdbc.sql("""
                        SELECT version_business_id FROM reporting_projection_active
                        WHERE singleton_id = 1
                        """).query(String.class).single())
                .isEqualTo(active.versionBusinessId());
        assertThat(asAdmin(() -> rebuilds.listVersions())).hasSize(1);
    }

    private ReportingRebuildRepository failingRepository() {
        return (ReportingRebuildRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {ReportingRebuildRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("copyLiveRecords")) {
                        throw new IllegalStateException("注入影子复制失败");
                    }
                    try {
                        return method.invoke(rebuildRepository, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }
}
