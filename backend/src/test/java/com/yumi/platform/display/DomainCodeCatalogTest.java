package com.yumi.platform.display;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.calculation.api.WorkloadComparison;
import com.yumi.customer.api.CustomerStatus;
import com.yumi.orders.api.OrderOrigin;
import com.yumi.orders.api.OrderPaymentType;
import com.yumi.orders.api.OrderStatus;
import com.yumi.orders.aftersales.api.AfterSalesBearerType;
import com.yumi.orders.aftersales.api.AfterSalesCaseStatus;
import com.yumi.orders.aftersales.api.AfterSalesObligationChangeRoute;
import com.yumi.orders.aftersales.api.AfterSalesObligationStatus;
import com.yumi.orders.aftersales.api.AfterSalesObligationType;
import com.yumi.orders.aftersales.api.AfterSalesRouteType;
import com.yumi.platform.aftercommit.api.AfterCommitTaskStatus;
import com.yumi.platform.api.identity.AccountStatus;
import com.yumi.product.api.MaterialSource;
import com.yumi.product.api.ProductStatus;
import com.yumi.product.api.SchemeType;
import com.yumi.production.api.ArrangementMode;
import com.yumi.production.api.DefectDispositionType;
import com.yumi.production.api.DefectReasonStatus;
import com.yumi.production.api.DemandStatus;
import com.yumi.production.api.DemandType;
import com.yumi.production.api.OpeningStageCode;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.ResponsibilityType;
import com.yumi.production.api.ReturnDispositionType;
import com.yumi.production.api.SourceKind;
import com.yumi.production.api.SubjectTaskSlot;
import com.yumi.production.api.SubjectType;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.api.TaskStatus;
import com.yumi.production.api.TaskType;
import com.yumi.production.api.TransferReason;
import com.yumi.workforce.api.EmploymentStatus;
import com.yumi.workforce.api.QualificationStatus;
import com.yumi.workforce.api.ReservationStatus;
import com.yumi.workforce.api.TeamStatus;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 共享码表覆盖门禁：后端枚举常量必须有前端展示标签。 */
class DomainCodeCatalogTest {

    @Test
    @DisplayName("所有展示枚举都在共享码表中")
    void allDisplayedEnumConstantsExistInCatalog() throws Exception {
        JsonNode root = new ObjectMapper().readTree(
                Path.of("..", "frontend", "src", "platform", "display", "domain-codes.json").toFile());
        JsonNode categories = root.path("categories");

        Map<Class<? extends Enum<?>>, String> enums = new LinkedHashMap<>();
        enums.put(CustomerStatus.class, "common.status");
        enums.put(OrderStatus.class, "order.status");
        enums.put(OrderOrigin.class, "order.origin");
        enums.put(OrderPaymentType.class, "order.paymentType");
        enums.put(ProductStatus.class, "product.status");
        enums.put(SchemeType.class, "product.schemeType");
        enums.put(MaterialSource.class, "product.materialSource");
        enums.put(EmploymentStatus.class, "workforce.employmentStatus");
        enums.put(QualificationStatus.class, "workforce.qualificationStatus");
        enums.put(TeamStatus.class, "workforce.teamStatus");
        enums.put(ReservationStatus.class, "workforce.reservationStatus");
        enums.put(TaskType.class, "production.taskType");
        enums.put(TaskStatus.class, "production.taskStatus");
        enums.put(DemandType.class, "production.demandType");
        enums.put(DemandStatus.class, "production.demandStatus");
        enums.put(SubjectType.class, "production.subjectType");
        enums.put(TargetProcess.class, "production.targetProcess");
        enums.put(PhysicalDestination.class, "production.physicalDestination");
        enums.put(ResponsibilityType.class, "production.responsibilityType");
        enums.put(ReturnDispositionType.class, "production.returnDispositionType");
        enums.put(DefectReasonStatus.class, "production.defectReasonStatus");
        enums.put(TransferReason.class, "production.transferReason");
        enums.put(OpeningStageCode.class, "production.openingStageCode");
        enums.put(SourceKind.class, "production.sourceKind");
        enums.put(SubjectTaskSlot.class, "production.subjectTaskSlot");
        enums.put(ArrangementMode.class, "production.arrangementMode");
        enums.put(DefectDispositionType.class, "production.defectDispositionType");
        enums.put(WorkloadComparison.class, "production.workloadComparison");
        enums.put(AccountStatus.class, "platform.accountStatus");
        enums.put(AfterCommitTaskStatus.class, "platform.afterCommitTaskStatus");
        enums.put(AfterSalesCaseStatus.class, "afterSales.caseStatus");
        enums.put(AfterSalesObligationStatus.class, "afterSales.obligationStatus");
        enums.put(AfterSalesObligationType.class, "afterSales.obligationType");
        enums.put(AfterSalesRouteType.class, "afterSales.routeType");
        enums.put(AfterSalesObligationChangeRoute.class, "afterSales.changeRoute");
        enums.put(AfterSalesBearerType.class, "afterSales.bearerType");

        for (var entry : enums.entrySet()) {
            String category = entry.getValue();
            JsonNode catalog = categories.path(category);
            assertThat(catalog.isObject())
                    .as("码表缺少分类 " + category)
                    .isTrue();
            for (Enum<?> constant : entry.getKey().getEnumConstants()) {
                assertThat(catalog.has(constant.name()))
                        .as("码表 %s 缺少枚举 %s.%s", category,
                                entry.getKey().getSimpleName(), constant.name())
                        .isTrue();
                assertThat(catalog.path(constant.name()).path("label").asText())
                        .as("码表 %s.%s 缺少中文标签", category, constant.name())
                        .isNotBlank();
            }
        }
    }
}
