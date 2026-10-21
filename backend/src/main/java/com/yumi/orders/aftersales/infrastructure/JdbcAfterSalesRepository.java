package com.yumi.orders.aftersales.infrastructure;

import com.yumi.orders.aftersales.application.port.AfterSalesRepository;
import com.yumi.platform.api.jdbc.JdbcTimes;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 售后案例持久化（MySQL）。 */
@Repository
public class JdbcAfterSalesRepository implements AfterSalesRepository {

    private final JdbcClient jdbc;

    public JdbcAfterSalesRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertCase(CaseRecord record) {
        jdbc.sql("""
                        INSERT INTO after_sales_cases (
                            case_business_id, case_no, order_business_id, customer_business_id,
                            issue_summary, route_type, status, opened_at, content_version, row_version)
                        VALUES (:id, :caseNo, :orderId, :customerId, :summary, :route, :status,
                                :openedAt, 1, 1)
                        """)
                .param("id", record.caseBusinessId())
                .param("caseNo", record.caseNo())
                .param("orderId", record.orderBusinessId())
                .param("customerId", record.customerBusinessId())
                .param("summary", record.issueSummary())
                .param("route", record.routeType())
                .param("status", record.status())
                .param("openedAt", JdbcTimes.toUtc(record.openedAt()))
                .update();
    }

    @Override
    public void insertIssue(IssueRecord record) {
        jdbc.sql("""
                        INSERT INTO after_sales_issue_facts (
                            fact_business_id, case_business_id, issue_type, severity, description,
                            evidence_count, occurred_at, is_valid)
                        VALUES (:id, :caseId, :type, :severity, :description, :evidenceCount,
                                :occurredAt, 1)
                        """)
                .param("id", record.factBusinessId())
                .param("caseId", record.caseBusinessId())
                .param("type", record.issueType())
                .param("severity", record.severity())
                .param("description", record.description())
                .param("evidenceCount", record.evidenceCount())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .update();
    }

    @Override
    public void insertObligation(ObligationRecord record) {
        jdbc.sql("""
                        INSERT INTO after_sales_obligations (
                            obligation_business_id, case_business_id, order_item_business_id,
                            obligation_type, bearer_type, predecessor_obligation_business_id,
                            created_by_change_business_id, target_quantity, target_amount,
                            fulfilled_quantity, fulfilled_amount, due_date, note, status, row_version)
                        VALUES (:id, :caseId, :orderItemId, :type, :bearer, :predecessor, :createdBy,
                                :targetQuantity, :targetAmount, 0, 0, :dueDate, :note, :status, 1)
                        """)
                .param("id", record.obligationBusinessId())
                .param("caseId", record.caseBusinessId())
                .param("orderItemId", record.orderItemBusinessId())
                .param("type", record.obligationType())
                .param("bearer", record.bearerType())
                .param("predecessor", record.predecessorObligationBusinessId())
                .param("createdBy", record.createdByChangeBusinessId())
                .param("targetQuantity", record.targetQuantity())
                .param("targetAmount", record.targetAmount())
                .param("dueDate", record.dueDate() == null ? null : java.sql.Date.valueOf(record.dueDate()))
                .param("note", record.note())
                .param("status", record.status())
                .update();
    }

    @Override
    public Optional<CaseRecord> findCase(String caseBusinessId) {
        return queryCase(caseBusinessId, false);
    }

    @Override
    public Optional<CaseRecord> lockCase(String caseBusinessId) {
        return queryCase(caseBusinessId, true);
    }

    private Optional<CaseRecord> queryCase(String caseBusinessId, boolean lock) {
        return jdbc.sql("""
                        SELECT case_business_id, case_no, order_business_id, customer_business_id,
                               issue_summary, route_type, status, opened_at, updated_at,
                               content_version, row_version
                        FROM after_sales_cases
                        WHERE case_business_id = :id
                        """ + (lock ? " FOR UPDATE" : ""))
                .param("id", caseBusinessId)
                .query((resultSet, rowNumber) -> new CaseRecord(
                        resultSet.getString("case_business_id"),
                        resultSet.getString("case_no"),
                        resultSet.getString("order_business_id"),
                        resultSet.getString("customer_business_id"),
                        resultSet.getString("issue_summary"),
                        resultSet.getString("route_type"),
                        resultSet.getString("status"),
                        JdbcTimes.toInstant(resultSet.getObject("opened_at", java.time.LocalDateTime.class)),
                        JdbcTimes.toInstant(resultSet.getObject("updated_at", java.time.LocalDateTime.class)),
                        resultSet.getLong("content_version"),
                        resultSet.getLong("row_version")))
                .optional();
    }

    @Override
    public List<ObligationRecord> findObligations(String caseBusinessId) {
        return queryObligations(caseBusinessId, false);
    }

    @Override
    public List<ObligationRecord> lockObligations(String caseBusinessId) {
        return queryObligations(caseBusinessId, true);
    }

    private List<ObligationRecord> queryObligations(String caseBusinessId, boolean lock) {
        return jdbc.sql("""
                        SELECT obligation_business_id, case_business_id, order_item_business_id,
                               obligation_type, bearer_type, predecessor_obligation_business_id,
                               created_by_change_business_id, target_quantity, target_amount,
                               fulfilled_quantity, fulfilled_amount, due_date, note, status, row_version
                        FROM after_sales_obligations
                        WHERE case_business_id = :caseId
                        ORDER BY obligation_business_id
                        """ + (lock ? " FOR UPDATE" : ""))
                .param("caseId", caseBusinessId)
                .query((resultSet, rowNumber) -> new ObligationRecord(
                        resultSet.getString("obligation_business_id"),
                        resultSet.getString("case_business_id"),
                        resultSet.getString("order_item_business_id"),
                        resultSet.getString("obligation_type"),
                        resultSet.getString("bearer_type"),
                        resultSet.getString("predecessor_obligation_business_id"),
                        resultSet.getString("created_by_change_business_id"),
                        (Integer) resultSet.getObject("target_quantity"),
                        resultSet.getBigDecimal("target_amount"),
                        resultSet.getInt("fulfilled_quantity"),
                        resultSet.getBigDecimal("fulfilled_amount"),
                        resultSet.getObject("due_date", LocalDate.class),
                        resultSet.getString("note"),
                        resultSet.getString("status"),
                        resultSet.getLong("row_version")))
                .list();
    }

    @Override
    public void updateObligationStatus(
            String obligationBusinessId, String status, long expectedRowVersion) {
        int updated = jdbc.sql("""
                        UPDATE after_sales_obligations
                        SET status = :status, row_version = row_version + 1
                        WHERE obligation_business_id = :id AND row_version = :version
                        """)
                .param("status", status)
                .param("id", obligationBusinessId)
                .param("version", expectedRowVersion)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("售后义务版本冲突：" + obligationBusinessId);
        }
    }

    @Override
    public void insertCompletion(CompletionRecord record) {
        jdbc.sql("""
                        INSERT INTO after_sales_completion_facts (
                            fact_business_id, case_business_id, fact_type, obligation_fingerprint,
                            occurred_at, operator_id, is_valid)
                        VALUES (:id, :caseId, :type, :fingerprint, :occurredAt, :operatorId, 1)
                        """)
                .param("id", record.factBusinessId())
                .param("caseId", record.caseBusinessId())
                .param("type", record.factType())
                .param("fingerprint", record.obligationFingerprint())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public Optional<CompletionRecord> findCompletion(String caseBusinessId) {
        return jdbc.sql("""
                        SELECT fact_business_id, case_business_id, fact_type, obligation_fingerprint,
                               occurred_at, operator_id
                        FROM after_sales_completion_facts
                        WHERE case_business_id = :id AND is_valid = 1 AND fact_type = 'COMPLETED'
                        """)
                .param("id", caseBusinessId)
                .query((resultSet, rowNumber) -> new CompletionRecord(
                        resultSet.getString("fact_business_id"),
                        resultSet.getString("case_business_id"),
                        resultSet.getString("fact_type"),
                        resultSet.getString("obligation_fingerprint"),
                        JdbcTimes.toInstant(resultSet.getObject("occurred_at", java.time.LocalDateTime.class)),
                        resultSet.getString("operator_id")))
                .optional();
    }

    @Override
    public void markCaseCompleted(String caseBusinessId) {
        jdbc.sql("""
                        UPDATE after_sales_cases
                        SET status = 'COMPLETED', content_version = content_version + 1,
                            row_version = row_version + 1, updated_at = UTC_TIMESTAMP(6)
                        WHERE case_business_id = :id
                        """)
                .param("id", caseBusinessId)
                .update();
    }

    @Override
    public Optional<ObligationRecord> lockObligation(String obligationBusinessId) {
        return jdbc.sql("""
                        SELECT obligation_business_id, case_business_id, order_item_business_id,
                               obligation_type, bearer_type, predecessor_obligation_business_id,
                               created_by_change_business_id, target_quantity, target_amount,
                               fulfilled_quantity, fulfilled_amount, due_date, note, status, row_version
                        FROM after_sales_obligations
                        WHERE obligation_business_id = :id FOR UPDATE
                        """)
                .param("id", obligationBusinessId)
                .query(JdbcAfterSalesRepository::mapObligation)
                .optional();
    }

    @Override
    public Optional<ObligationRecord> findObligation(String obligationBusinessId) {
        return jdbc.sql("""
                        SELECT obligation_business_id, case_business_id, order_item_business_id,
                               obligation_type, bearer_type, predecessor_obligation_business_id,
                               created_by_change_business_id, target_quantity, target_amount,
                               fulfilled_quantity, fulfilled_amount, due_date, note, status, row_version
                        FROM after_sales_obligations WHERE obligation_business_id = :id
                        """)
                .param("id", obligationBusinessId)
                .query(JdbcAfterSalesRepository::mapObligation)
                .optional();
    }

    @Override
    public void updateObligationProgress(
            String obligationBusinessId, int fulfilledQuantity, java.math.BigDecimal fulfilledAmount,
            String status, long expectedRowVersion) {
        int updated = jdbc.sql("""
                        UPDATE after_sales_obligations
                        SET fulfilled_quantity = :quantity, fulfilled_amount = :amount,
                            status = :status, row_version = row_version + 1
                        WHERE obligation_business_id = :id AND row_version = :version
                        """)
                .param("quantity", fulfilledQuantity)
                .param("amount", fulfilledAmount)
                .param("status", status)
                .param("id", obligationBusinessId)
                .param("version", expectedRowVersion)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("售后义务版本冲突：" + obligationBusinessId);
        }
    }

    @Override
    public void insertReceiptFact(ReceiptFactRecord record) {
        jdbc.sql("""
                        INSERT INTO after_sales_return_receipt_facts (
                            fact_business_id, obligation_business_id, received_total_quantity,
                            condition_code, received_at, operator_id, is_valid)
                        VALUES (:id, :obligationId, :quantity, :condition, :receivedAt, :operatorId, 1)
                        """)
                .param("id", record.factBusinessId())
                .param("obligationId", record.obligationBusinessId())
                .param("quantity", record.receivedTotalQuantity())
                .param("condition", record.conditionCode())
                .param("receivedAt", JdbcTimes.toUtc(record.receivedAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public void insertReceiptItem(ReceiptItemRecord record) {
        jdbc.sql("""
                        INSERT INTO after_sales_return_receipt_items (
                            receipt_item_business_id, receipt_fact_business_id, obligation_business_id,
                            prior_delivery_type, prior_delivery_business_id,
                            prior_delivery_item_business_id, prior_delivery_source_type,
                            prior_production_source_business_id, prior_returned_source_business_id,
                            physical_root_source_business_id, prior_consumption_fact_business_id,
                            production_returned_source_business_id, quantity)
                        VALUES (:id, :factId, :obligationId, :deliveryType, :deliveryId,
                                :deliveryItemId, :sourceType, :productionSourceId, :returnedSourceId,
                                :physicalRoot, :consumptionId, :newReturnedSourceId, :quantity)
                        """)
                .param("id", record.receiptItemBusinessId())
                .param("factId", record.receiptFactBusinessId())
                .param("obligationId", record.obligationBusinessId())
                .param("deliveryType", record.priorDeliveryType())
                .param("deliveryId", record.priorDeliveryBusinessId())
                .param("deliveryItemId", record.priorDeliveryItemBusinessId())
                .param("sourceType", record.priorDeliverySourceType())
                .param("productionSourceId", record.priorProductionSourceBusinessId())
                .param("returnedSourceId", record.priorReturnedSourceBusinessId())
                .param("physicalRoot", record.physicalRootSourceBusinessId())
                .param("consumptionId", record.priorConsumptionFactBusinessId())
                .param("newReturnedSourceId", record.productionReturnedSourceBusinessId())
                .param("quantity", record.quantity())
                .update();
    }

    @Override
    public Optional<ReceiptItemRecord> findReceiptItem(String receiptItemBusinessId) {
        return jdbc.sql(RECEIPT_ITEM_SELECT + " WHERE receipt_item_business_id = :id")
                .param("id", receiptItemBusinessId)
                .query(JdbcAfterSalesRepository::mapReceiptItem)
                .optional();
    }

    @Override
    public List<ReceiptItemRecord> findReceiptItems(String obligationBusinessId) {
        return jdbc.sql(RECEIPT_ITEM_SELECT + " WHERE obligation_business_id = :id ORDER BY receipt_item_business_id")
                .param("id", obligationBusinessId)
                .query(JdbcAfterSalesRepository::mapReceiptItem)
                .list();
    }

    @Override
    public Optional<DeliveryCandidate> findLatestDelivery(String orderItemBusinessId) {
        return jdbc.sql("""
                        SELECT * FROM (
                            SELECT 'SHIPMENT' AS delivery_type, s.shipment_business_id AS delivery_id,
                                   i.shipment_item_business_id AS delivery_item_id,
                                   'PRODUCTION_SOURCE' AS source_type,
                                   i.production_source_business_id AS production_source_id,
                                   NULL AS returned_source_id,
                                   p.physical_root_source_business_id AS physical_root,
                                   i.production_consumption_fact_business_id AS consumption_id,
                                   i.quantity, s.shipped_at AS delivered_at
                            FROM orders_shipment_items i
                            JOIN orders_shipments s ON s.shipment_business_id = i.shipment_business_id
                            JOIN orders_agreement_groups g
                              ON g.agreement_group_business_id = i.agreement_group_business_id
                            JOIN production_sources p
                              ON p.production_source_business_id = i.production_source_business_id
                            WHERE g.order_item_business_id = :itemId AND s.is_valid = 1
                            UNION ALL
                            SELECT 'AFTER_SALES_DELIVERY', f.fact_business_id,
                                   di.delivery_item_business_id, di.delivery_source_type,
                                   di.production_source_business_id,
                                   di.production_returned_source_business_id,
                                   di.physical_root_source_business_id,
                                   di.production_consumption_fact_business_id,
                                   di.quantity, f.delivered_at
                            FROM after_sales_delivery_items di
                            JOIN after_sales_delivery_facts f
                              ON f.fact_business_id = di.delivery_fact_business_id
                            WHERE f.order_item_business_id = :itemId AND f.is_valid = 1
                        ) deliveries
                        ORDER BY delivered_at DESC, delivery_item_id DESC
                        LIMIT 1
                        """)
                .param("itemId", orderItemBusinessId)
                .query((resultSet, rowNumber) -> new DeliveryCandidate(
                        resultSet.getString("delivery_type"),
                        resultSet.getString("delivery_id"),
                        resultSet.getString("delivery_item_id"),
                        resultSet.getString("source_type"),
                        resultSet.getString("production_source_id"),
                        resultSet.getString("returned_source_id"),
                        resultSet.getString("physical_root"),
                        resultSet.getString("consumption_id"),
                        resultSet.getInt("quantity"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "delivered_at", java.time.LocalDateTime.class))))
                .optional();
    }

    @Override
    public int returnedQuantityOfDeliveryItem(String deliveryItemBusinessId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(quantity), 0) FROM after_sales_return_receipt_items
                        WHERE prior_delivery_item_business_id = :id
                        """).param("id", deliveryItemBusinessId).query(Integer.class).single();
    }

    @Override
    public int disposedQuantityOfReceiptItem(String receiptItemBusinessId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(quantity), 0) FROM after_sales_return_disposition_facts
                        WHERE return_receipt_item_business_id = :id AND is_valid = 1
                        """).param("id", receiptItemBusinessId).query(Integer.class).single();
    }

    @Override
    public void insertDeliveryFact(DeliveryFactRecord record) {
        jdbc.sql("""
                        INSERT INTO after_sales_delivery_facts (
                            fact_business_id, obligation_business_id, order_item_business_id,
                            delivery_type, delivered_at, recipient_payload_json, tracking_no,
                            operator_id, is_valid)
                        VALUES (:id, :obligationId, :itemId, :type, :deliveredAt, :recipient,
                                :trackingNo, :operatorId, 1)
                        """)
                .param("id", record.factBusinessId())
                .param("obligationId", record.obligationBusinessId())
                .param("itemId", record.orderItemBusinessId())
                .param("type", record.deliveryType())
                .param("deliveredAt", JdbcTimes.toUtc(record.deliveredAt()))
                .param("recipient", record.recipientPayloadJson())
                .param("trackingNo", record.trackingNo())
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public void insertDeliveryItem(DeliveryItemRecord record) {
        jdbc.sql("""
                        INSERT INTO after_sales_delivery_items (
                            delivery_item_business_id, delivery_fact_business_id, delivery_source_type,
                            production_source_business_id, production_returned_source_business_id,
                            physical_root_source_business_id, quantity,
                            production_consumption_fact_business_id)
                        VALUES (:id, :factId, :sourceType, :productionSourceId, :returnedSourceId,
                                :physicalRoot, :quantity, :consumptionId)
                        """)
                .param("id", record.deliveryItemBusinessId())
                .param("factId", record.deliveryFactBusinessId())
                .param("sourceType", record.deliverySourceType())
                .param("productionSourceId", record.productionSourceBusinessId())
                .param("returnedSourceId", record.productionReturnedSourceBusinessId())
                .param("physicalRoot", record.physicalRootSourceBusinessId())
                .param("quantity", record.quantity())
                .param("consumptionId", record.productionConsumptionFactBusinessId())
                .update();
    }

    private static final String RECEIPT_ITEM_SELECT = """
            SELECT receipt_item_business_id, receipt_fact_business_id, obligation_business_id,
                   prior_delivery_type, prior_delivery_business_id, prior_delivery_item_business_id,
                   prior_delivery_source_type, prior_production_source_business_id,
                   prior_returned_source_business_id, physical_root_source_business_id,
                   prior_consumption_fact_business_id, production_returned_source_business_id, quantity
            FROM after_sales_return_receipt_items
            """;

    private static ReceiptItemRecord mapReceiptItem(
            java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new ReceiptItemRecord(
                resultSet.getString("receipt_item_business_id"),
                resultSet.getString("receipt_fact_business_id"),
                resultSet.getString("obligation_business_id"),
                resultSet.getString("prior_delivery_type"),
                resultSet.getString("prior_delivery_business_id"),
                resultSet.getString("prior_delivery_item_business_id"),
                resultSet.getString("prior_delivery_source_type"),
                resultSet.getString("prior_production_source_business_id"),
                resultSet.getString("prior_returned_source_business_id"),
                resultSet.getString("physical_root_source_business_id"),
                resultSet.getString("prior_consumption_fact_business_id"),
                resultSet.getString("production_returned_source_business_id"),
                resultSet.getInt("quantity"));
    }

    private static ObligationRecord mapObligation(
            java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new ObligationRecord(
                resultSet.getString("obligation_business_id"),
                resultSet.getString("case_business_id"),
                resultSet.getString("order_item_business_id"),
                resultSet.getString("obligation_type"),
                resultSet.getString("bearer_type"),
                resultSet.getString("predecessor_obligation_business_id"),
                resultSet.getString("created_by_change_business_id"),
                (Integer) resultSet.getObject("target_quantity"),
                resultSet.getBigDecimal("target_amount"),
                resultSet.getInt("fulfilled_quantity"),
                resultSet.getBigDecimal("fulfilled_amount"),
                resultSet.getObject("due_date", LocalDate.class),
                resultSet.getString("note"),
                resultSet.getString("status"),
                resultSet.getLong("row_version"));
    }

    @Override
    public int receivedQuantityOfObligation(String obligationBusinessId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(quantity), 0) FROM after_sales_return_receipt_items
                        WHERE obligation_business_id = :id
                        """).param("id", obligationBusinessId).query(Integer.class).single();
    }

    @Override
    public void insertDisposition(DispositionRecord record) {
        jdbc.sql("""
                        INSERT INTO after_sales_return_disposition_facts (
                            fact_business_id, obligation_business_id, return_receipt_item_business_id,
                            production_returned_source_business_id, disposition_type,
                            physical_destination, production_terminal_exit_business_id, quantity,
                            production_disposition_fact_business_id,
                            production_consumption_fact_business_id,
                            production_offline_transfer_fact_business_id, reason_text,
                            business_date, occurred_at, operator_id, is_valid)
                        VALUES (:id, :obligationId, :receiptItemId, :returnedSourceId, :type,
                                :destination, :exitId, :quantity, :productionDispositionId,
                                :consumptionId, :transferId, :reasonText, :businessDate,
                                :occurredAt, :operatorId, 1)
                        """)
                .param("id", record.factBusinessId())
                .param("obligationId", record.obligationBusinessId())
                .param("receiptItemId", record.receiptItemBusinessId())
                .param("returnedSourceId", record.productionReturnedSourceBusinessId())
                .param("type", record.dispositionType())
                .param("destination", record.physicalDestination())
                .param("exitId", record.productionTerminalExitBusinessId())
                .param("quantity", record.quantity())
                .param("productionDispositionId", record.productionDispositionFactBusinessId())
                .param("consumptionId", record.productionConsumptionFactBusinessId())
                .param("transferId", record.productionOfflineTransferFactBusinessId())
                .param("reasonText", record.reasonText())
                .param("businessDate", java.sql.Date.valueOf(record.businessDate()))
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public Optional<DeliveryItemRecord> findDeliveryItem(String deliveryItemBusinessId) {
        return jdbc.sql("""
                        SELECT delivery_item_business_id, delivery_fact_business_id,
                               delivery_source_type, production_source_business_id,
                               production_returned_source_business_id,
                               physical_root_source_business_id, quantity,
                               production_consumption_fact_business_id
                        FROM after_sales_delivery_items WHERE delivery_item_business_id = :id
                        """)
                .param("id", deliveryItemBusinessId)
                .query((resultSet, rowNumber) -> new DeliveryItemRecord(
                        resultSet.getString("delivery_item_business_id"),
                        resultSet.getString("delivery_fact_business_id"),
                        resultSet.getString("delivery_source_type"),
                        resultSet.getString("production_source_business_id"),
                        resultSet.getString("production_returned_source_business_id"),
                        resultSet.getString("physical_root_source_business_id"),
                        resultSet.getInt("quantity"),
                        resultSet.getString("production_consumption_fact_business_id")))
                .optional();
    }

    @Override
    public Optional<DeliveryFactRecord> findDeliveryFact(String deliveryFactBusinessId) {
        return jdbc.sql("""
                        SELECT fact_business_id, obligation_business_id, order_item_business_id,
                               delivery_type, delivered_at, recipient_payload_json, tracking_no,
                               operator_id
                        FROM after_sales_delivery_facts WHERE fact_business_id = :id
                        """)
                .param("id", deliveryFactBusinessId)
                .query((resultSet, rowNumber) -> new DeliveryFactRecord(
                        resultSet.getString("fact_business_id"),
                        resultSet.getString("obligation_business_id"),
                        resultSet.getString("order_item_business_id"),
                        resultSet.getString("delivery_type"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "delivered_at", java.time.LocalDateTime.class)),
                        resultSet.getString("recipient_payload_json"),
                        resultSet.getString("tracking_no"),
                        resultSet.getString("operator_id")))
                .optional();
    }

    @Override
    public void updateObligationTarget(
            String obligationBusinessId, Integer targetQuantity, java.math.BigDecimal targetAmount,
            String status, long expectedRowVersion) {
        int updated = jdbc.sql("""
                        UPDATE after_sales_obligations
                        SET target_quantity = :quantity, target_amount = :amount, status = :status,
                            row_version = row_version + 1
                        WHERE obligation_business_id = :id AND row_version = :version
                        """)
                .param("quantity", targetQuantity)
                .param("amount", targetAmount)
                .param("status", status)
                .param("id", obligationBusinessId)
                .param("version", expectedRowVersion)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("售后义务版本冲突：" + obligationBusinessId);
        }
    }

    @Override
    public void insertObligationFact(ObligationFactRecord record) {
        jdbc.sql("""
                        INSERT INTO after_sales_obligation_facts (
                            fact_business_id, obligation_business_id, operation_type, quantity,
                            amount, quantity_resolution_refs_json, note, occurred_at, operator_id,
                            is_valid)
                        VALUES (:id, :obligationId, :operation, :quantity, :amount, :refs, :note,
                                :occurredAt, :operatorId, 1)
                        """)
                .param("id", record.factBusinessId())
                .param("obligationId", record.obligationBusinessId())
                .param("operation", record.operationType())
                .param("quantity", record.quantity())
                .param("amount", record.amount())
                .param("refs", record.quantityResolutionRefsJson())
                .param("note", record.note())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public void insertProductionLink(ProductionLinkRecord record) {
        jdbc.sql("""
                        INSERT INTO after_sales_production_links (
                            link_business_id, obligation_business_id, return_receipt_item_business_id,
                            production_demand_business_id, production_repair_source_business_id,
                            demand_type, link_type)
                        VALUES (:id, :obligationId, :receiptItemId, :demandId, :repairSourceId,
                                :demandType, :linkType)
                        """)
                .param("id", record.linkBusinessId())
                .param("obligationId", record.obligationBusinessId())
                .param("receiptItemId", record.returnReceiptItemBusinessId())
                .param("demandId", record.productionDemandBusinessId())
                .param("repairSourceId", record.productionRepairSourceBusinessId())
                .param("demandType", record.demandType())
                .param("linkType", record.linkType())
                .update();
    }

    @Override
    public Optional<ProductionLinkRecord> findProductionLink(
            String obligationBusinessId, String linkType) {
        return jdbc.sql("""
                        SELECT link_business_id, obligation_business_id,
                               return_receipt_item_business_id, production_demand_business_id,
                               production_repair_source_business_id, demand_type, link_type
                        FROM after_sales_production_links
                        WHERE obligation_business_id = :id AND link_type = :type
                        """)
                .param("id", obligationBusinessId)
                .param("type", linkType)
                .query((resultSet, rowNumber) -> new ProductionLinkRecord(
                        resultSet.getString("link_business_id"),
                        resultSet.getString("obligation_business_id"),
                        resultSet.getString("return_receipt_item_business_id"),
                        resultSet.getString("production_demand_business_id"),
                        resultSet.getString("production_repair_source_business_id"),
                        resultSet.getString("demand_type"),
                        resultSet.getString("link_type")))
                .optional();
    }

    @Override
    public Optional<DispositionRecord> findDisposition(String factBusinessId) {
        return jdbc.sql("""
                        SELECT fact_business_id, obligation_business_id, return_receipt_item_business_id,
                               production_returned_source_business_id, disposition_type,
                               physical_destination, production_terminal_exit_business_id, quantity,
                               production_disposition_fact_business_id,
                               production_consumption_fact_business_id,
                               production_offline_transfer_fact_business_id, reason_text,
                               business_date, occurred_at, operator_id
                        FROM after_sales_return_disposition_facts WHERE fact_business_id = :id
                        """)
                .param("id", factBusinessId)
                .query((resultSet, rowNumber) -> new DispositionRecord(
                        resultSet.getString("fact_business_id"),
                        resultSet.getString("obligation_business_id"),
                        resultSet.getString("return_receipt_item_business_id"),
                        resultSet.getString("production_returned_source_business_id"),
                        resultSet.getString("disposition_type"),
                        resultSet.getString("physical_destination"),
                        resultSet.getString("production_terminal_exit_business_id"),
                        resultSet.getInt("quantity"),
                        resultSet.getString("production_disposition_fact_business_id"),
                        resultSet.getString("production_consumption_fact_business_id"),
                        resultSet.getString("production_offline_transfer_fact_business_id"),
                        resultSet.getString("reason_text"),
                        resultSet.getObject("business_date", LocalDate.class),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "occurred_at", java.time.LocalDateTime.class)),
                        resultSet.getString("operator_id")))
                .optional();
    }

    @Override
    public int maxCostSourceVersion(String sourceBusinessId) {
        return jdbc.sql("""
                        SELECT COALESCE(MAX(source_version), 0) FROM after_sales_cost_source_items
                        WHERE source_business_id = :id
                        """).param("id", sourceBusinessId).query(Integer.class).single();
    }

    @Override
    public void insertCostSource(CostSourceRecord record) {
        jdbc.sql("""
                        INSERT INTO after_sales_cost_source_items (
                            source_business_id, source_version, case_business_id, order_business_id,
                            cost_category, amount, business_date, operation_type, basis_ref,
                            reason_text, occurred_at, operator_id)
                        VALUES (:sourceId, :version, :caseId, :orderId, :category, :amount,
                                :businessDate, :operation, :basisRef, :reasonText, :occurredAt,
                                :operatorId)
                        """)
                .param("sourceId", record.sourceBusinessId())
                .param("version", record.sourceVersion())
                .param("caseId", record.caseBusinessId())
                .param("orderId", record.orderBusinessId())
                .param("category", record.costCategory())
                .param("amount", record.amount())
                .param("businessDate", java.sql.Date.valueOf(record.businessDate()))
                .param("operation", record.operationType())
                .param("basisRef", record.basisRef())
                .param("reasonText", record.reasonText())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("operatorId", "SYSTEM")
                .update();
    }

    @Override
    public List<CostSourceRecord> listCostSourcesAfter(long afterSequence, int limit) {
        return jdbc.sql("""
                        SELECT feed_sequence, source_business_id, source_version, case_business_id,
                               order_business_id, cost_category, amount, business_date,
                               operation_type, basis_ref, reason_text, occurred_at
                        FROM after_sales_cost_source_items
                        WHERE feed_sequence > :after
                        ORDER BY feed_sequence
                        LIMIT :limit
                        """)
                .param("after", afterSequence)
                .param("limit", limit)
                .query(JdbcAfterSalesRepository::mapCostSource)
                .list();
    }

    @Override
    public List<CostSourceRecord> findCostSourcesByCase(String caseBusinessId) {
        return jdbc.sql("""
                        SELECT feed_sequence, source_business_id, source_version, case_business_id,
                               order_business_id, cost_category, amount, business_date,
                               operation_type, basis_ref, reason_text, occurred_at
                        FROM after_sales_cost_source_items
                        WHERE case_business_id = :caseId
                        ORDER BY feed_sequence
                        """)
                .param("caseId", caseBusinessId)
                .query(JdbcAfterSalesRepository::mapCostSource)
                .list();
    }

    @Override
    public Optional<CostSourceRecord> findLatestCostSource(String sourceBusinessId) {
        return jdbc.sql("""
                        SELECT feed_sequence, source_business_id, source_version, case_business_id,
                               order_business_id, cost_category, amount, business_date,
                               operation_type, basis_ref, reason_text, occurred_at
                        FROM after_sales_cost_source_items
                        WHERE source_business_id = :id
                        ORDER BY source_version DESC, feed_sequence DESC
                        LIMIT 1
                        """)
                .param("id", sourceBusinessId)
                .query(JdbcAfterSalesRepository::mapCostSource)
                .optional();
    }

    @Override
    public List<CaseRecord> searchCases(
            String keyword, String status, int offset, int limit) {
        return jdbc.sql("""
                        SELECT case_business_id, case_no, order_business_id, customer_business_id,
                               issue_summary, route_type, status, opened_at, updated_at,
                               content_version, row_version
                        FROM after_sales_cases
                        WHERE (:keyword = '' OR case_no LIKE CONCAT('%', :keyword, '%')
                               OR order_business_id = :keyword)
                          AND (:status = '' OR status = :status)
                        ORDER BY opened_at DESC, case_business_id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("keyword", keyword == null ? "" : keyword)
                .param("status", status == null ? "" : status)
                .param("limit", limit)
                .param("offset", offset)
                .query(JdbcAfterSalesRepository::mapCase)
                .list();
    }

    @Override
    public List<CaseRecord> findCasesChangedAfter(
            Instant updatedAfter, String afterCaseBusinessId, int limit) {
        return jdbc.sql("""
                        SELECT case_business_id, case_no, order_business_id, customer_business_id,
                               issue_summary, route_type, status, opened_at, updated_at,
                               content_version, row_version
                        FROM after_sales_cases
                        WHERE updated_at > :after
                           OR (updated_at = :after AND case_business_id > :afterId)
                        ORDER BY updated_at, case_business_id
                        LIMIT :limit
                        """)
                .param("after", JdbcTimes.toUtc(updatedAfter))
                .param("afterId", afterCaseBusinessId == null ? "" : afterCaseBusinessId)
                .param("limit", limit)
                .query(JdbcAfterSalesRepository::mapCase)
                .list();
    }

    private static CostSourceRecord mapCostSource(
            java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new CostSourceRecord(
                resultSet.getLong("feed_sequence"),
                resultSet.getString("source_business_id"),
                resultSet.getInt("source_version"),
                resultSet.getString("case_business_id"),
                resultSet.getString("order_business_id"),
                resultSet.getString("cost_category"),
                resultSet.getBigDecimal("amount"),
                resultSet.getObject("business_date", LocalDate.class),
                resultSet.getString("operation_type"),
                resultSet.getString("basis_ref"),
                resultSet.getString("reason_text"),
                JdbcTimes.toInstant(resultSet.getObject("occurred_at", java.time.LocalDateTime.class)));
    }

    private static CaseRecord mapCase(
            java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new CaseRecord(
                resultSet.getString("case_business_id"),
                resultSet.getString("case_no"),
                resultSet.getString("order_business_id"),
                resultSet.getString("customer_business_id"),
                resultSet.getString("issue_summary"),
                resultSet.getString("route_type"),
                resultSet.getString("status"),
                JdbcTimes.toInstant(resultSet.getObject("opened_at", java.time.LocalDateTime.class)),
                JdbcTimes.toInstant(resultSet.getObject("updated_at", java.time.LocalDateTime.class)),
                resultSet.getLong("content_version"),
                resultSet.getLong("row_version"));
    }

    @Override
    public void insertPaymentLink(AfterSalesPaymentLinkRecord record) {
        jdbc.sql("""
                        INSERT INTO after_sales_payment_link_facts (
                            fact_business_id, obligation_business_id, finance_cash_fact_business_id,
                            linked_amount, is_valid, corrects_fact_business_id, occurred_at,
                            operator_id)
                        VALUES (:id, :obligationId, :cashFactId, :amount, 1, :correctsId,
                                :occurredAt, :operatorId)
                        """)
                .param("id", record.factBusinessId())
                .param("obligationId", record.obligationBusinessId())
                .param("cashFactId", record.financeCashFactBusinessId())
                .param("amount", record.linkedAmount())
                .param("correctsId", record.correctsFactBusinessId())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public Optional<AfterSalesPaymentLinkRecord> findPaymentLink(String factBusinessId) {
        return jdbc.sql("""
                        SELECT fact_business_id, obligation_business_id,
                               finance_cash_fact_business_id, linked_amount,
                               corrects_fact_business_id, occurred_at, operator_id
                        FROM after_sales_payment_link_facts WHERE fact_business_id = :id
                        """)
                .param("id", factBusinessId)
                .query(JdbcAfterSalesRepository::mapPaymentLink)
                .optional();
    }

    @Override
    public Optional<AfterSalesPaymentLinkRecord> findPaymentLinkByCashFact(
            String financeCashFactBusinessId) {
        return jdbc.sql("""
                        SELECT fact_business_id, obligation_business_id,
                               finance_cash_fact_business_id, linked_amount,
                               corrects_fact_business_id, occurred_at, operator_id
                        FROM after_sales_payment_link_facts
                        WHERE finance_cash_fact_business_id = :id
                          AND corrects_fact_business_id IS NULL AND is_valid = 1
                        """)
                .param("id", financeCashFactBusinessId)
                .query(JdbcAfterSalesRepository::mapPaymentLink)
                .optional();
    }

    @Override
    public Optional<AfterSalesPaymentLinkRecord> findPaymentLinkReversal(String originalFactId) {
        return jdbc.sql("""
                        SELECT fact_business_id, obligation_business_id,
                               finance_cash_fact_business_id, linked_amount,
                               corrects_fact_business_id, occurred_at, operator_id
                        FROM after_sales_payment_link_facts
                        WHERE corrects_fact_business_id = :id AND is_valid = 1
                        """)
                .param("id", originalFactId)
                .query(JdbcAfterSalesRepository::mapPaymentLink)
                .optional();
    }

    private static AfterSalesPaymentLinkRecord mapPaymentLink(
            java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new AfterSalesPaymentLinkRecord(
                resultSet.getString("fact_business_id"),
                resultSet.getString("obligation_business_id"),
                resultSet.getString("finance_cash_fact_business_id"),
                resultSet.getBigDecimal("linked_amount"),
                resultSet.getString("corrects_fact_business_id"),
                JdbcTimes.toInstant(resultSet.getObject(
                        "occurred_at", java.time.LocalDateTime.class)),
                resultSet.getString("operator_id"));
    }
}
