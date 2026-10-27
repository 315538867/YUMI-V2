package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.orders.api.OrderRef;
import com.yumi.orders.api.ShipmentItemRequest;
import com.yumi.orders.api.http.OrderController;
import com.yumi.platform.api.http.HttpHeaders;
import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.LossRate;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.platform.api.value.WeightGram;
import com.yumi.product.api.CreateProductCommand;
import com.yumi.product.api.MaterialSource;
import com.yumi.production.api.ArrangeTaskCommand;
import com.yumi.production.api.SubjectRef;
import com.yumi.production.api.SubjectType;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.api.TaskItemRequest;
import com.yumi.production.api.TaskItemResultRequest;
import com.yumi.production.api.TaskType;
import com.yumi.production.api.VerifyTaskCommand;
import com.yumi.support.AbstractOrderFixtureTest;
import com.yumi.workforce.api.EmployeeRef;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 交付清单业务 HTTP 门禁（13.7/13.8）。
 *
 * <p>真实 Spring 上下文、真实认证过滤器、真实 MySQL：逐模块验证正式路由、授权、
 * 幂等、精度与拒绝路径，内部 Feed/Participant 不暴露为网页路由。
 */
@AutoConfigureMockMvc
class BusinessHttpCoverageIT extends AbstractOrderFixtureTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("F04/R01/R02：现金收付、月汇总、归集与影子重建路由")
    void cashAndReportingRoutes() throws Exception {
        OrderRef order = confirmOrderForMaking(2);

        MvcResult receipt = command("/api/finance/cash/order-receipts", Map.of(
                        "orderBusinessId", order.orderBusinessId(),
                        "amount", Map.of("amount", "176.00", "currency", "CNY"),
                        "businessDate", "2026-10-20"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cashFactBusinessId").isNotEmpty())
                .andExpect(jsonPath("$.cashType").value("ORDER_RECEIPT"))
                .andExpect(jsonPath("$.amount.amount").value("176.00"))
                .andReturn();
        String cashFactId = json(receipt).get("cashFactBusinessId").asText();

        getAsAdmin("/api/finance/cash/facts",
                "startDate", "2026-10-01", "endDate", "2026-10-31")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cashFactBusinessId").value(cashFactId));

        command("/api/reporting/administration/ingestions", Map.of())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recordCount").isNumber());
        getAsAdmin("/api/reporting/monthly-summary", "month", "2026-10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCount").value(1))
                .andExpect(jsonPath("$.orderReceivable").value(176.00))
                .andExpect(jsonPath("$.costIncomplete").value(true));

        command("/api/reporting/administration/rebuilds", Map.of())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activated").value(true))
                .andExpect(jsonPath("$.differenceCount").value(0));

        getAsAdmin("/api/reporting/freshness")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feeds.length()").value(9))
                .andExpect(jsonPath("$.late").value(false));
        getAsAdmin("/api/reporting/administration/versions")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));

        mockMvc.perform(get("/api/reporting/monthly-summary").param("month", "2026-10"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("C01/D01/D02：客户与商品正式命令、幂等、冲突及配置读取")
    void customerProductAndConfigurationRoutes() throws Exception {
        String key = "HTTP-" + UUID.randomUUID();
        Map<String, Object> createCustomer = Map.of(
                "name", "HTTP 客户", "contactName", "李四",
                "phone", "13900000000", "defaultAddress", "上海市接口路 1 号");

        MvcResult created = command("/api/customers", key, createCustomer)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("HTTP 客户"))
                .andReturn();
        String customerId = json(created).get("customerBusinessId").asText();
        command("/api/customers", key, createCustomer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerBusinessId").value(customerId));

        getAsAdmin("/api/customers/" + customerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("HTTP 客户"));
        getAsAdmin("/api/customers", "keyword", "HTTP")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].customerBusinessId").value(customerId));

        mockMvc.perform(put("/api/customers/" + customerId)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .header(HttpHeaders.IDEMPOTENCY_KEY, "HTTP-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(Map.of(
                                "name", "改名", "contactName", "李四",
                                "phone", "13900000000", "defaultAddress", "新地址",
                                "expectedRowVersion", 999))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/customers")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(createCustomer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("VALIDATION"));

        getAsAdmin("/api/products")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productBusinessId").isNotEmpty());
        getAsAdmin("/api/products/" + defaultProduct.productBusinessId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("标准商品"));
        getAsAdmin("/api/products/" + defaultProduct.productBusinessId() + "/schemes")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].schemeType").value("SYSTEM_STANDARD"));

        MvcResult product = command("/api/products", productDefinition())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("HTTP 商品"))
                .andReturn();
        String productId = json(product).get("productBusinessId").asText();
        getAsAdmin("/api/products/" + productId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.standardSalePrice.amount").value("50.00"));

        getAsAdmin("/api/product-entry-configuration")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.makingStars").isArray());
    }

    @Test
    @DisplayName("W01/W02/N01：人员、标准与生产任务真实路由")
    void workforceAndProductionRoutes() throws Exception {
        getAsAdmin("/api/workforce/employees").andExpect(status().isOk());
        getAsAdmin("/api/workforce/teams").andExpect(status().isOk());
        getAsAdmin("/api/workforce/standards")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyStandardBusinessId").value("WFD-DAILY"));

        MvcResult hired = command("/api/workforce/employees", Map.of(
                        "name", "HTTP 工人", "phone", "13700000000",
                        "effectiveDate", "2026-10-01"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("HTTP 工人"))
                .andReturn();
        assertThat(json(hired).get("employeeBusinessId").asText()).isNotBlank();

        EmployeeRef employee = hireEmployee("生产接口工人", "MAKING");
        OrderRef order = confirmOrderForMaking(2);
        String sourceId = orderSourceId(order);
        ArrangeTaskCommand arrange = new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION,
                new SubjectRef(SubjectType.EMPLOYEE, employee.employeeBusinessId()),
                LocalDate.parse("2026-10-06"), TargetProcess.MAKING,
                List.of(new TaskItemRequest(sourceId, 2)), null, null, null);

        MvcResult arranged = command("/api/production/tasks/arrangements", arrange)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taskBusinessId").isNotEmpty())
                .andReturn();
        String taskId = json(arranged).get("taskBusinessId").asText();
        MvcResult task = getAsAdmin("/api/production/tasks/" + taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].plannedQuantity").value(2))
                .andReturn();
        String itemId = json(task).get("items").get(0).get("taskItemBusinessId").asText();

        command("/api/production/tasks/verification", new VerifyTaskCommand(List.of(
                new TaskItemResultRequest(itemId, 0, 1, 0, 1,
                        List.of(new com.yumi.production.api.DefectGroupRequest(
                                "OTHER", "接口报废", 1, 0,
                                List.of(new com.yumi.production.api.DefectGroupRequest.ScrapPartRequest(
                                        1, com.yumi.production.api.PhysicalDestination
                                                .TRANSFERRED_OFFLINE_INVENTORY,
                                        com.yumi.production.api.ResponsibilityType.STUDIO,
                                        "STUDIO", null)))),
                        null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskBusinessId").value(taskId));
        getAsAdmin("/api/production/offline-transfers",
                "productionSourceBusinessId", sourceId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].quantity").value(1))
                .andExpect(jsonPath("$[0].destination").value("TRANSFERRED_OFFLINE_INVENTORY"));

        getAsAdmin("/api/production/tasks/demands", "productBusinessId",
                defaultProduct.productBusinessId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        getAsAdmin("/api/production/quality/defect-reasons")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        String orderItemId = orderJdbc.sql("""
                        SELECT order_item_business_id FROM orders_items
                        WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(String.class).single();
        getAsAdmin("/api/production/orders/" + orderItemId + "/trace")
                .andExpect(status().isOk());

        EmployeeRef cancelWorker = hireEmployee(
                "接口取消工人-" + java.util.UUID.randomUUID(), "MAKING");
        var otherTaskBody = new java.util.HashMap<String, Object>();
        otherTaskBody.put("taskType", "OTHER_TASK");
        otherTaskBody.put("subject", Map.of(
                "subjectType", "EMPLOYEE",
                "subjectBusinessId", cancelWorker.employeeBusinessId()));
        otherTaskBody.put("workDate", "2026-10-07");
        otherTaskBody.put("processCode", null);
        otherTaskBody.put("items", List.of());
        otherTaskBody.put("enteredStandardMinutes", 30);
        otherTaskBody.put("otherTask", Map.of("workContent", "接口取消任务"));
        otherTaskBody.put("overrideReason", null);
        MvcResult otherTask = command("/api/production/tasks/arrangements", otherTaskBody)
                .andExpect(status().isCreated())
                .andReturn();
        String otherTaskId = json(otherTask).get("taskBusinessId").asText();
        command("/api/production/tasks/" + otherTaskId + "/cancellation",
                Map.of("reasonText", "接口取消"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.plannedWorkQuantity").value(0));

        EmployeeRef adjustWorker = hireEmployee(
                "接口调整工人-" + java.util.UUID.randomUUID());
        var adjustArrangeBody = new java.util.HashMap<String, Object>();
        adjustArrangeBody.put("taskType", "OTHER_TASK");
        adjustArrangeBody.put("subject", Map.of(
                "subjectType", "EMPLOYEE",
                "subjectBusinessId", adjustWorker.employeeBusinessId()));
        adjustArrangeBody.put("workDate", "2026-10-08");
        adjustArrangeBody.put("processCode", null);
        adjustArrangeBody.put("items", List.of());
        adjustArrangeBody.put("enteredStandardMinutes", 30);
        adjustArrangeBody.put("otherTask", Map.of("workContent", "接口调整前任务"));
        adjustArrangeBody.put("overrideReason", null);
        MvcResult adjustableTask = command(
                "/api/production/tasks/arrangements", adjustArrangeBody)
                .andExpect(status().isCreated())
                .andReturn();
        String adjustableTaskId = json(adjustableTask).get("taskBusinessId").asText();

        var adjustBody = new java.util.HashMap<String, Object>();
        adjustBody.put("subject", Map.of(
                "subjectType", "EMPLOYEE",
                "subjectBusinessId", adjustWorker.employeeBusinessId()));
        adjustBody.put("workDate", "2026-10-09");
        adjustBody.put("processCode", null);
        adjustBody.put("items", List.of());
        adjustBody.put("enteredStandardMinutes", 45);
        adjustBody.put("otherTask", Map.of("workContent", "接口调整后任务"));
        adjustBody.put("overrideReason", null);
        String adjustKey = "HTTP-ADJUST-" + java.util.UUID.randomUUID();
        String adjustPath = "/api/production/tasks/" + adjustableTaskId + "/adjustment";
        command(adjustPath, adjustKey, adjustBody)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workDate").value("2026-10-09"))
                .andExpect(jsonPath("$.taskStandardSeconds").value(2700))
                .andExpect(jsonPath("$.otherTask.workContent").value("接口调整后任务"));
        command(adjustPath, adjustKey, adjustBody)
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("O01/O02：订单确认、查询与发货真实路由")
    void orderAndDeliveryRoutes() throws Exception {
        OrderController.CreateOrderRequest request = new OrderController.CreateOrderRequest(
                defaultCustomer.customerBusinessId(), null, ORDER_DATE, ORDER_DATE.plusDays(7),
                null,
List.of(new com.yumi.orders.api.OrderItemRequest(
                        defaultProduct.productBusinessId(), standardSchemeSnapshotRef(),
                        com.yumi.product.api.SchemeView.ProcessCode.MAKING, 2,
                        money("88.00"),
                        new com.yumi.orders.api.OrderItemRequest.MaterialSourceAgreement(
                                MaterialSource.STUDIO, MaterialSource.STUDIO,
                                MaterialSource.STUDIO, MaterialSource.STUDIO),
                        new com.yumi.orders.api.RecipientInfo("张三", "13800000000", "地址"))));
        MvcResult created = command("/api/orders", request)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderBusinessId").isNotEmpty())
                .andReturn();
        String orderId = json(created).get("orderBusinessId").asText();
        OrderRef ref = new OrderRef(orderId, null, null, 0, null, 0, 0);

        getAsAdmin("/api/orders/" + orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderBusinessId").value(orderId));
        getAsAdmin("/api/orders", "status", "CONFIRMED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderBusinessId").isNotEmpty());

        String sourceId = orderSourceId(ref);
        produceAtMaking(sourceId, 2);
        Map<String, Object> shipment = Map.of(
                "orderBusinessId", orderId,
                "items", List.of(new ShipmentItemRequest(orderGroupId(ref), sourceId, 2)),
                "trackingNo", "SF-HTTP-1",
                "shippedAt", LocalDateTime.parse("2026-10-08T10:00:00"));
        command("/api/orders/delivery/shipments", shipment)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderBusinessId").value(orderId))
                .andExpect(jsonPath("$.deliveryFulfilled").value(true));
        getAsAdmin("/api/orders/delivery/fulfillment/" + orderId)
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A01/A02：售后受理、详情与费用确认真实路由")
    void afterSalesRoutes() throws Exception {
        OrderRef order = completedOrder(1);
        Map<String, Object> openCase = Map.of(
                "orderBusinessId", order.orderBusinessId(),
                "issueSummary", "接口售后",
                "routeType", "REFUND_ONLY",
                "obligations", List.of(Map.of(
                        "obligationType", "REFUND",
                        "targetAmount", Map.of("amount", "50.00", "currency", "CNY"),
                        "dueDate", "2026-10-30",
                        "note", "退款")));
        MvcResult opened = command("/api/after-sales/cases", openCase)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.caseBusinessId").isNotEmpty())
                .andReturn();
        String caseId = json(opened).get("caseBusinessId").asText();

        getAsAdmin("/api/after-sales/cases/" + caseId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueSummary").value("接口售后"))
                .andExpect(jsonPath("$.obligations[0].obligationType").value("REFUND"));
        getAsAdmin("/api/after-sales/cases", "keyword", order.orderBusinessId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].caseBusinessId").value(caseId));

        command("/api/after-sales/costs/confirmations", Map.of(
                        "caseBusinessId", caseId,
                        "costCategory", "AFTER_SALES_GOODWILL",
                        "amount", Map.of("amount", "12.00", "currency", "CNY"),
                        "businessDate", "2026-10-20",
                        "reasonText", "接口确认费用"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceBusinessId").isNotEmpty());
        getAsAdmin("/api/after-sales/costs", "caseBusinessId", caseId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceBusinessId").isNotEmpty());
    }

    @Test
    @DisplayName("F01/F02/F03：工资期间、胶水设置与成本台账真实路由")
    void payrollAndCostRoutes() throws Exception {
        String periodNo = "HTTP-202610";
        MvcResult period = command("/api/payroll/periods", Map.of(
                        "periodNo", periodNo,
                        "startDate", "2026-10-01", "endDate", "2026-10-31"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.periodBusinessId").isNotEmpty())
                .andReturn();
        String periodId = json(period).get("periodBusinessId").asText();

        getAsAdmin("/api/payroll/periods")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].periodNo").value(periodNo));
        command("/api/payroll/calculations", Map.of("periodBusinessId", periodId))
                .andExpect(status().is2xxSuccessful());
        getAsAdmin("/api/payroll/periods/" + periodId + "/results")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        command("/api/payroll/glue/policies", Map.of(
                        "effectiveMonth", "2026-10-01",
                        "exemptWeightGrams", "300.0000",
                        "reasonText", "接口设置",
                        "expectedGuardVersion", 0))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveMonth").value("2026-10-01"));
        getAsAdmin("/api/payroll/glue/policies", "month", "2026-10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exemptWeightGrams").value("300.0000"));

        OrderRef order = confirmOrderForMaking(2);
        produceAtMaking(orderSourceId(order), 2);
        command("/api/finance/cost-ledger/material-accruals", Map.of(
                        "startDate", "2026-10-01", "endDate", "2026-10-31"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accruedCount").isNumber());
        getAsAdmin("/api/finance/cost-ledger/entries",
                "subjectType", "ORDER", "subjectId", order.orderBusinessId(),
                "startDate", "2026-10-01", "endDate", "2026-10-31")
                .andExpect(status().isOk());
        getAsAdmin("/api/finance/cost-ledger/failures")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    private ResultActions command(String path, Object body) throws Exception {
        String key = "HTTP-" + UUID.randomUUID();
        return command(path, key, body);
    }

    private ResultActions command(String path, String idempotencyKey, Object body)
            throws Exception {
        return mockMvc.perform(post(path)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .header(HttpHeaders.IDEMPOTENCY_KEY, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody(body)));
    }

    private ResultActions getAsAdmin(String path, String... params) throws Exception {
        var builder = get(path).header(HttpHeaders.AUTHORIZATION, bearer());
        for (int index = 0; index < params.length; index += 2) {
            builder.param(params[index], params[index + 1]);
        }
        return mockMvc.perform(builder);
    }

    private String bearer() {
        return "Bearer " + adminToken;
    }

    private String jsonBody(Object body) throws Exception {
        return body instanceof String text ? text : objectMapper.writeValueAsString(body);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static SettlementMoney money(String amount) {
        return SettlementMoney.of(new BigDecimal(amount), CurrencyCode.CNY);
    }

    private static CostAmount cost(String amount) {
        return CostAmount.of(new BigDecimal(amount), CurrencyCode.CNY);
    }

    private static CreateProductCommand productDefinition() {
        CostAmount one = cost("1.00");
        return new CreateProductCommand(
                "HTTP 商品", "接口商品", WeightGram.ofGrams("120.0000"), "4", 30,
                money("50.00"), LossRate.of(new BigDecimal("0.0500")),
                one, one, one, 4, 10, one, 5,
                one, one, one, one, 4, one, one, one,
                one, one, one, one, one, one);
    }
}
