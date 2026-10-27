package com.yumi.orders.infrastructure;

import com.yumi.orders.application.port.OrderRepository;
import com.yumi.platform.api.jdbc.JdbcTimes;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 订单持久化（MySQL）。 */
@Repository
public class JdbcOrderRepository implements OrderRepository {

    private final JdbcClient jdbc;

    public JdbcOrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertOrder(OrderRecord record) {
        jdbc.sql("""
                        INSERT INTO orders_orders (
                            order_business_id, order_no, origin, customer_business_id,
                            customer_snapshot_ref, order_date, expected_delivery_date, order_note, status,
                            current_receivable_amount, currency_code, delivery_fulfilled,
                            payment_fulfilled, content_version, row_version, created_at, created_by,
                            updated_at)
                        VALUES (:orderId, :orderNo, :origin, :customerId, :customerSnapshotRef, :orderDate,
                                :expectedDeliveryDate, :note, :status, :receivable, :currency,
                                :deliveryFulfilled, :paymentFulfilled, 1, 1, :createdAt, :createdBy,
                                :updatedAt)
                        """)
                .param("orderId", record.orderBusinessId())
                .param("orderNo", record.orderNo())
                .param("origin", record.origin())
                .param("customerId", record.customerBusinessId())
                .param("customerSnapshotRef", record.customerSnapshotRef())
                .param("orderDate", Date.valueOf(record.orderDate()))
                .param("expectedDeliveryDate", Date.valueOf(record.expectedDeliveryDate()))
                .param("note", record.note())
                .param("status", record.status())
                .param("receivable", record.currentReceivableAmount())
                .param("currency", record.currencyCode())
                .param("deliveryFulfilled", record.deliveryFulfilled())
                .param("paymentFulfilled", record.paymentFulfilled())
                .param("createdAt", JdbcTimes.toUtc(record.createdAt()))
                .param("createdBy", record.createdBy())
                .param("updatedAt", JdbcTimes.toUtc(record.updatedAt()))
                .update();
    }

    @Override
    public void insertConfirmationFact(ConfirmationRecord record) {
        jdbc.sql("""
                        INSERT INTO orders_confirmation_facts (
                            fact_business_id, order_business_id, confirmation_type, total_quantity,
                            total_amount, occurred_at, operator_id)
                        VALUES (:factId, :orderId, :type, :quantity, :amount, :occurredAt, :operatorId)
                        """)
                .param("factId", record.factBusinessId())
                .param("orderId", record.orderBusinessId())
                .param("type", record.confirmationType())
                .param("quantity", record.totalQuantity())
                .param("amount", record.totalAmount())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public void insertItem(ItemRecord record) {
        jdbc.sql("""
                        INSERT INTO orders_items (
                            order_item_business_id, order_business_id, item_no,
                            predecessor_order_item_business_id, created_by_change_business_id,
                            product_business_id, product_snapshot_ref, ordered_quantity, active_quantity,
                            status, row_version)
                        VALUES (:itemId, :orderId, :itemNo, NULL, NULL, :productId, :productSnapshotRef,
                                :orderedQuantity, :activeQuantity, :status, 1)
                        """)
                .param("itemId", record.orderItemBusinessId())
                .param("orderId", record.orderBusinessId())
                .param("itemNo", record.itemNo())
                .param("productId", record.productBusinessId())
                .param("productSnapshotRef", record.productSnapshotRef())
                .param("orderedQuantity", record.orderedQuantity())
                .param("activeQuantity", record.activeQuantity())
                .param("status", record.status())
                .update();
    }

    @Override
    public void insertAgreementGroup(AgreementGroupRecord record) {
        jdbc.sql("""
                        INSERT INTO orders_agreement_groups (
                            agreement_group_business_id, order_item_business_id, active_version_no,
                            active_quantity, fulfilled_quantity, status, row_version)
                        VALUES (:groupId, :itemId, :versionNo, :activeQuantity, :fulfilledQuantity,
                                :status, 1)
                        """)
                .param("groupId", record.agreementGroupBusinessId())
                .param("itemId", record.orderItemBusinessId())
                .param("versionNo", record.activeVersionNo())
                .param("activeQuantity", record.activeQuantity())
                .param("fulfilledQuantity", record.fulfilledQuantity())
                .param("status", record.status())
                .update();
    }

    @Override
    public void insertAgreementVersion(AgreementVersionRecord record) {
        jdbc.sql("""
                        INSERT INTO orders_agreement_versions (
                            agreement_version_business_id, group_business_id, version_no,
                            scheme_snapshot_ref, target_process, unit_price, quantity, glue_source,
                            color_paste_source, bag_source, decoration_source, production_basis_json,
                            production_basis_fingerprint, recipient_payload_json, expected_cost_payload_json,
                            effective_fact_business_id, content_fingerprint, created_at)
                        VALUES (:versionId, :groupId, :versionNo, :schemeSnapshotRef, :targetProcess,
                                :unitPrice, :quantity, :glueSource, :colorPasteSource, :bagSource,
                                :decorationSource, :basisJson, :basisFingerprint, :recipientJson,
                                :expectedCostJson, :effectiveFactId, :contentFingerprint, :createdAt)
                        """)
                .param("versionId", record.agreementVersionBusinessId())
                .param("groupId", record.groupBusinessId())
                .param("versionNo", record.versionNo())
                .param("schemeSnapshotRef", record.schemeSnapshotRef())
                .param("targetProcess", record.targetProcess())
                .param("unitPrice", record.unitPrice())
                .param("quantity", record.quantity())
                .param("glueSource", record.glueSource())
                .param("colorPasteSource", record.colorPasteSource())
                .param("bagSource", record.bagSource())
                .param("decorationSource", record.decorationSource())
                .param("basisJson", record.productionBasisJson())
                .param("basisFingerprint", record.productionBasisFingerprint())
                .param("recipientJson", record.recipientPayloadJson())
                .param("expectedCostJson", record.expectedCostPayloadJson())
                .param("effectiveFactId", record.effectiveFactBusinessId())
                .param("contentFingerprint", record.contentFingerprint())
                .param("createdAt", JdbcTimes.toUtc(record.createdAt()))
                .update();
    }

    @Override
    public void insertAgreementProcess(AgreementProcessRecord record) {
        jdbc.sql("""
                        INSERT INTO orders_agreement_processes (
                            agreement_version_business_id, process_code, standard_minutes, labor_cost,
                            material_cost, other_cost, source_terms_json)
                        VALUES (:versionId, :processCode, :minutes, :laborCost, :materialCost, :otherCost,
                                :sourceTermsJson)
                        """)
                .param("versionId", record.agreementVersionBusinessId())
                .param("processCode", record.processCode())
                .param("minutes", record.standardMinutes())
                .param("laborCost", record.laborCost())
                .param("materialCost", record.materialCost())
                .param("otherCost", record.otherCost())
                .param("sourceTermsJson", record.sourceTermsJson())
                .update();
    }

    @Override
    public void insertReceivableFact(ReceivableRecord record) {
        jdbc.sql("""
                        INSERT INTO orders_receivable_facts (
                            fact_business_id, order_business_id, fact_type, amount_delta, currency_code,
                            source_change_business_id, is_valid, occurred_at)
                        VALUES (:factId, :orderId, :factType, :amountDelta, :currency, :changeId, 1,
                                :occurredAt)
                        """)
                .param("factId", record.factBusinessId())
                .param("orderId", record.orderBusinessId())
                .param("factType", record.factType())
                .param("amountDelta", record.amountDelta())
                .param("currency", record.currencyCode())
                .param("changeId", record.sourceChangeBusinessId())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .update();
    }

    @Override
    public void insertFulfillmentProjection(ProjectionRecord record) {
        jdbc.sql("""
                        INSERT INTO orders_fulfillment_projections (
                            order_business_id, required_quantity, shipped_quantity, current_receivable,
                            received_amount, refunded_amount, delivery_fulfilled, payment_fulfilled,
                            blocking_reason_codes, updated_at, row_version)
                        VALUES (:orderId, :required, :shipped, :receivable, :received, :refunded,
                                :deliveryFulfilled, :paymentFulfilled, :blocking, UTC_TIMESTAMP(6), 1)
                        ON DUPLICATE KEY UPDATE
                            required_quantity = VALUES(required_quantity),
                            shipped_quantity = VALUES(shipped_quantity),
                            current_receivable = VALUES(current_receivable),
                            received_amount = VALUES(received_amount),
                            refunded_amount = VALUES(refunded_amount),
                            delivery_fulfilled = VALUES(delivery_fulfilled),
                            payment_fulfilled = VALUES(payment_fulfilled),
                            blocking_reason_codes = VALUES(blocking_reason_codes),
                            updated_at = UTC_TIMESTAMP(6), row_version = row_version + 1
                        """)
                .param("orderId", record.orderBusinessId())
                .param("required", record.requiredQuantity())
                .param("shipped", record.shippedQuantity())
                .param("receivable", record.currentReceivable())
                .param("received", record.receivedAmount())
                .param("refunded", record.refundedAmount())
                .param("deliveryFulfilled", record.deliveryFulfilled())
                .param("paymentFulfilled", record.paymentFulfilled())
                .param("blocking", record.blockingReasonCodes())
                .update();
    }

    @Override
    public void updateOrderReceivable(
            String orderBusinessId, java.math.BigDecimal receivable, long expectedContentVersion) {
        int updated = jdbc.sql("""
                        UPDATE orders_orders
                        SET current_receivable_amount = :receivable,
                            content_version = content_version + 1, row_version = row_version + 1,
                            updated_at = UTC_TIMESTAMP(6)
                        WHERE order_business_id = :id AND content_version = :version
                        """)
                .param("receivable", receivable)
                .param("id", orderBusinessId)
                .param("version", expectedContentVersion)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("订单版本冲突：" + orderBusinessId);
        }
    }

    @Override
    public void updateItemActiveQuantity(String orderItemBusinessId, int activeQuantity, String status) {
        jdbc.sql("""
                        UPDATE orders_items SET active_quantity = :quantity, status = :status,
                            row_version = row_version + 1
                        WHERE order_item_business_id = :id
                        """)
                .param("quantity", activeQuantity)
                .param("status", status)
                .param("id", orderItemBusinessId)
                .update();
    }

    @Override
    public void updateGroupActiveQuantity(
            String agreementGroupBusinessId, int activeQuantity, int activeVersionNo, String status) {
        jdbc.sql("""
                        UPDATE orders_agreement_groups
                        SET active_quantity = :quantity, active_version_no = :versionNo, status = :status,
                            row_version = row_version + 1
                        WHERE agreement_group_business_id = :id
                        """)
                .param("quantity", activeQuantity)
                .param("versionNo", activeVersionNo)
                .param("status", status)
                .param("id", agreementGroupBusinessId)
                .update();
    }

    @Override
    public void insertChangeFact(ChangeRecord record) {
        jdbc.sql("""
                        INSERT INTO orders_change_facts (
                            change_business_id, order_business_id, change_type, reason_text, amount_delta,
                            occurred_at, operator_id, is_valid)
                        VALUES (:changeId, :orderId, :changeType, :reason, :delta, :occurredAt, :operatorId, 1)
                        """)
                .param("changeId", record.changeBusinessId())
                .param("orderId", record.orderBusinessId())
                .param("changeType", record.changeType())
                .param("reason", record.reasonText())
                .param("delta", record.amountDelta())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public void insertChangeAction(ChangeActionRecord record) {
        jdbc.sql("""
                        INSERT INTO orders_change_actions (
                            change_business_id, source_order_item_business_id, source_group_business_id,
                            successor_order_item_business_id, successor_group_business_id,
                            source_production_demand_business_id, successor_production_demand_business_id,
                            action_type, migrated_quantity, quantity_before, quantity_after,
                            old_version_no, new_version_no)
                        VALUES (:changeId, :sourceItemId, :sourceGroupId, :successorItemId,
                                :successorGroupId, :sourceDemandId, :successorDemandId, :actionType,
                                :migrated, :before, :after, :oldVersion, :newVersion)
                        """)
                .param("changeId", record.changeBusinessId())
                .param("sourceItemId", record.sourceOrderItemBusinessId())
                .param("sourceGroupId", record.sourceGroupBusinessId())
                .param("successorItemId", record.successorOrderItemBusinessId())
                .param("successorGroupId", record.successorGroupBusinessId())
                .param("sourceDemandId", record.sourceProductionDemandBusinessId())
                .param("successorDemandId", record.successorProductionDemandBusinessId())
                .param("actionType", record.actionType())
                .param("migrated", record.migratedQuantity())
                .param("before", record.quantityBefore())
                .param("after", record.quantityAfter())
                .param("oldVersion", record.oldVersionNo())
                .param("newVersion", record.newVersionNo())
                .update();
    }

    @Override
    public void insertOfflineTransferLink(
            String changeBusinessId, String groupBusinessId, String productionSourceBusinessId,
            String productionTransferBusinessId, int quantity) {
        jdbc.sql("""
                        INSERT INTO orders_change_offline_transfer_links (
                            change_business_id, group_business_id, production_source_business_id,
                            production_transfer_business_id, quantity)
                        VALUES (:changeId, :groupId, :sourceId, :transferId, :quantity)
                        """)
                .param("changeId", changeBusinessId)
                .param("groupId", groupBusinessId)
                .param("sourceId", productionSourceBusinessId)
                .param("transferId", productionTransferBusinessId)
                .param("quantity", quantity)
                .update();
    }

    @Override
    public Optional<ItemRecord> findItem(String orderItemBusinessId) {
        return jdbc.sql("""
                        SELECT order_item_business_id, order_business_id, item_no, product_business_id,
                               product_snapshot_ref, ordered_quantity, active_quantity, status, row_version
                        FROM orders_items WHERE order_item_business_id = :id
                        """)
                .param("id", orderItemBusinessId)
                .query((resultSet, rowNumber) -> new ItemRecord(
                        resultSet.getString("order_item_business_id"),
                        resultSet.getString("order_business_id"),
                        resultSet.getInt("item_no"),
                        resultSet.getString("product_business_id"),
                        resultSet.getString("product_snapshot_ref"),
                        resultSet.getInt("ordered_quantity"),
                        resultSet.getInt("active_quantity"),
                        resultSet.getString("status"),
                        resultSet.getLong("row_version")))
                .optional();
    }

    @Override
    public String findProductionDemandIdOfItem(String orderItemBusinessId) {
        return jdbc.sql("""
                        SELECT demand_business_id FROM production_demands
                        WHERE order_item_business_id = :id
                        """)
                .param("id", orderItemBusinessId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "订单项没有生产需求：" + orderItemBusinessId));
    }

    @Override
    public String findProductionSourceIdOfDemand(String demandBusinessId) {
        return jdbc.sql("""
                        SELECT production_source_business_id FROM production_sources
                        WHERE demand_business_id = :id ORDER BY id LIMIT 1
                        """)
                .param("id", demandBusinessId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "生产需求没有履约来源：" + demandBusinessId));
    }

    @Override
    public void insertShipment(ShipmentRecord record) {
        jdbc.sql("""
                        INSERT INTO orders_shipments (
                            shipment_business_id, shipment_no, order_business_id, shipped_at,
                            recipient_payload_json, tracking_no, operator_id, is_valid)
                        VALUES (:id, :no, :orderId, :shippedAt, :recipientJson, :trackingNo, :operatorId, 1)
                        """)
                .param("id", record.shipmentBusinessId())
                .param("no", record.shipmentNo())
                .param("orderId", record.orderBusinessId())
                .param("shippedAt", JdbcTimes.toUtc(record.shippedAt()))
                .param("recipientJson", record.recipientPayloadJson())
                .param("trackingNo", record.trackingNo())
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public void insertShipmentItem(ShipmentItemRecord record) {
        jdbc.sql("""
                        INSERT INTO orders_shipment_items (
                            shipment_item_business_id, shipment_business_id, agreement_group_business_id,
                            agreement_version_business_id, production_source_business_id,
                            production_consumption_fact_business_id, quantity)
                        VALUES (:id, :shipmentId, :groupId, :versionId, :sourceId, :consumptionId, :quantity)
                        """)
                .param("id", record.shipmentItemBusinessId())
                .param("shipmentId", record.shipmentBusinessId())
                .param("groupId", record.agreementGroupBusinessId())
                .param("versionId", record.agreementVersionBusinessId())
                .param("sourceId", record.productionSourceBusinessId())
                .param("consumptionId", record.productionConsumptionFactBusinessId())
                .param("quantity", record.quantity())
                .update();
    }

    @Override
    public void insertShipmentSourceLink(
            String shipmentBusinessId, String productionSourceBusinessId,
            String productionConsumptionFactBusinessId, int quantity) {
        jdbc.sql("""
                        INSERT INTO orders_shipment_source_links (
                            shipment_business_id, production_source_business_id,
                            production_consumption_fact_business_id, quantity)
                        VALUES (:shipmentId, :sourceId, :consumptionId, :quantity)
                        """)
                .param("shipmentId", shipmentBusinessId)
                .param("sourceId", productionSourceBusinessId)
                .param("consumptionId", productionConsumptionFactBusinessId)
                .param("quantity", quantity)
                .update();
    }

    @Override
    public void insertShipmentCorrection(CorrectionRecord record) {
        jdbc.sql("""
                        INSERT INTO orders_shipment_correction_facts (
                            fact_business_id, shipment_business_id, shipment_item_business_id,
                            correction_type, quantity_delta, reason_text, occurred_at, operator_id, is_valid)
                        VALUES (:id, :shipmentId, :itemId, :type, :delta, :reason, :occurredAt, :operatorId, 1)
                        """)
                .param("id", record.factBusinessId())
                .param("shipmentId", record.shipmentBusinessId())
                .param("itemId", record.shipmentItemBusinessId())
                .param("type", record.correctionType())
                .param("delta", record.quantityDelta())
                .param("reason", record.reasonText())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public Optional<ShipmentRecord> findShipment(String shipmentBusinessId) {
        return jdbc.sql("""
                        SELECT shipment_business_id, shipment_no, order_business_id, shipped_at,
                               recipient_payload_json, tracking_no, operator_id
                        FROM orders_shipments WHERE shipment_business_id = :id
                        """)
                .param("id", shipmentBusinessId)
                .query((resultSet, rowNumber) -> new ShipmentRecord(
                        resultSet.getString("shipment_business_id"),
                        resultSet.getString("shipment_no"),
                        resultSet.getString("order_business_id"),
                        JdbcTimes.toInstant(resultSet.getObject("shipped_at", java.time.LocalDateTime.class)),
                        resultSet.getString("recipient_payload_json"),
                        resultSet.getString("tracking_no"),
                        resultSet.getString("operator_id")))
                .optional();
    }

    @Override
    public Optional<ShipmentItemRecord> findShipmentItem(String shipmentItemBusinessId) {
        return jdbc.sql("""
                        SELECT shipment_item_business_id, shipment_business_id, agreement_group_business_id,
                               agreement_version_business_id, production_source_business_id,
                               production_consumption_fact_business_id, quantity
                        FROM orders_shipment_items WHERE shipment_item_business_id = :id
                        """)
                .param("id", shipmentItemBusinessId)
                .query(JdbcOrderRepository::mapShipmentItem)
                .optional();
    }

    @Override
    public java.util.List<ShipmentItemRecord> findShipmentItems(String shipmentBusinessId) {
        return jdbc.sql("""
                        SELECT shipment_item_business_id, shipment_business_id, agreement_group_business_id,
                               agreement_version_business_id, production_source_business_id,
                               production_consumption_fact_business_id, quantity
                        FROM orders_shipment_items WHERE shipment_business_id = :id
                        """)
                .param("id", shipmentBusinessId)
                .query(JdbcOrderRepository::mapShipmentItem)
                .list();
    }

    @Override
    public java.util.List<ShipmentItemRecord> findShipmentItemsOfOrder(String orderBusinessId) {
        return jdbc.sql("""
                        SELECT i.shipment_item_business_id, i.shipment_business_id,
                               i.agreement_group_business_id, i.agreement_version_business_id,
                               i.production_source_business_id,
                               i.production_consumption_fact_business_id, i.quantity
                        FROM orders_shipment_items i
                        JOIN orders_shipments s ON s.shipment_business_id = i.shipment_business_id
                        WHERE s.order_business_id = :id AND s.is_valid = 1
                        """)
                .param("id", orderBusinessId)
                .query(JdbcOrderRepository::mapShipmentItem)
                .list();
    }

    @Override
    public java.util.List<ShipmentOfOrderRecord> findShipmentsOfOrder(String orderBusinessId) {
        return jdbc.sql("""
                        SELECT shipment_business_id, shipment_no, shipped_at, tracking_no, is_valid
                        FROM orders_shipments
                        WHERE order_business_id = :id
                        ORDER BY shipped_at, shipment_business_id
                        """)
                .param("id", orderBusinessId)
                .query((resultSet, rowNumber) -> new ShipmentOfOrderRecord(
                        resultSet.getString("shipment_business_id"),
                        resultSet.getString("shipment_no"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "shipped_at", java.time.LocalDateTime.class)),
                        resultSet.getString("tracking_no"),
                        resultSet.getBoolean("is_valid")))
                .list();
    }

    @Override
    public java.util.List<ShipmentItemOfOrderRecord> findShipmentItemsOfOrderView(
            String orderBusinessId) {
        return jdbc.sql("""
                        SELECT i.shipment_item_business_id, i.shipment_business_id,
                               g.order_item_business_id, i.production_source_business_id,
                               i.quantity,
                               i.quantity + COALESCE(SUM(CASE WHEN c.is_valid = 1
                                   THEN c.quantity_delta ELSE 0 END), 0) AS effective_quantity
                        FROM orders_shipment_items i
                        JOIN orders_shipments s
                          ON s.shipment_business_id = i.shipment_business_id
                        JOIN orders_agreement_groups g
                          ON g.agreement_group_business_id = i.agreement_group_business_id
                        LEFT JOIN orders_shipment_correction_facts c
                          ON c.shipment_item_business_id = i.shipment_item_business_id
                        WHERE s.order_business_id = :id
                        GROUP BY i.shipment_item_business_id, i.shipment_business_id,
                                 g.order_item_business_id, i.production_source_business_id,
                                 i.quantity
                        ORDER BY i.shipment_item_business_id
                        """)
                .param("id", orderBusinessId)
                .query((resultSet, rowNumber) -> new ShipmentItemOfOrderRecord(
                        resultSet.getString("shipment_item_business_id"),
                        resultSet.getString("shipment_business_id"),
                        resultSet.getString("order_item_business_id"),
                        resultSet.getString("production_source_business_id"),
                        resultSet.getInt("quantity"),
                        resultSet.getInt("effective_quantity")))
                .list();
    }

    @Override
    public java.util.List<CorrectionOfOrderRecord> findCorrectionsOfOrder(String orderBusinessId) {
        return jdbc.sql("""
                        SELECT c.fact_business_id, c.shipment_business_id,
                               c.shipment_item_business_id, c.correction_type,
                               c.quantity_delta, c.reason_text, c.occurred_at, c.is_valid
                        FROM orders_shipment_correction_facts c
                        JOIN orders_shipments s
                          ON s.shipment_business_id = c.shipment_business_id
                        WHERE s.order_business_id = :id
                        ORDER BY c.occurred_at, c.fact_business_id
                        """)
                .param("id", orderBusinessId)
                .query((resultSet, rowNumber) -> new CorrectionOfOrderRecord(
                        resultSet.getString("fact_business_id"),
                        resultSet.getString("shipment_business_id"),
                        resultSet.getString("shipment_item_business_id"),
                        resultSet.getString("correction_type"),
                        resultSet.getInt("quantity_delta"),
                        resultSet.getString("reason_text"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "occurred_at", java.time.LocalDateTime.class)),
                        resultSet.getBoolean("is_valid")))
                .list();
    }

    @Override
    public java.util.List<PaymentLinkOfOrderRecord> findPaymentLinksOfOrder(
            String orderBusinessId) {
        return jdbc.sql("""
                        SELECT fact_business_id, finance_cash_fact_business_id, link_type,
                               linked_amount, reverses_fact_business_id, is_valid, occurred_at
                        FROM orders_payment_link_facts
                        WHERE order_business_id = :id
                        ORDER BY occurred_at, fact_business_id
                        """)
                .param("id", orderBusinessId)
                .query((resultSet, rowNumber) -> new PaymentLinkOfOrderRecord(
                        resultSet.getString("fact_business_id"),
                        resultSet.getString("finance_cash_fact_business_id"),
                        resultSet.getString("link_type"),
                        resultSet.getBigDecimal("linked_amount"),
                        resultSet.getString("reverses_fact_business_id"),
                        resultSet.getBoolean("is_valid"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "occurred_at", java.time.LocalDateTime.class))))
                .list();
    }

    @Override
    public java.util.List<ConfirmationRecord> findConfirmationsOfOrder(String orderBusinessId) {
        return jdbc.sql("""
                        SELECT fact_business_id, order_business_id, confirmation_type,
                               total_quantity, total_amount, occurred_at, operator_id
                        FROM orders_confirmation_facts
                        WHERE order_business_id = :id
                        ORDER BY occurred_at, fact_business_id
                        """)
                .param("id", orderBusinessId)
                .query((resultSet, rowNumber) -> new ConfirmationRecord(
                        resultSet.getString("fact_business_id"),
                        resultSet.getString("order_business_id"),
                        resultSet.getString("confirmation_type"),
                        resultSet.getInt("total_quantity"),
                        resultSet.getBigDecimal("total_amount"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "occurred_at", java.time.LocalDateTime.class)),
                        resultSet.getString("operator_id")))
                .list();
    }

    @Override
    public java.util.List<ChangeRecord> findChangesOfOrder(String orderBusinessId) {
        return jdbc.sql("""
                        SELECT change_business_id, order_business_id, change_type, reason_text,
                               amount_delta, occurred_at, operator_id
                        FROM orders_change_facts
                        WHERE order_business_id = :id AND is_valid = 1
                        ORDER BY occurred_at, change_business_id
                        """)
                .param("id", orderBusinessId)
                .query((resultSet, rowNumber) -> new ChangeRecord(
                        resultSet.getString("change_business_id"),
                        resultSet.getString("order_business_id"),
                        resultSet.getString("change_type"),
                        resultSet.getString("reason_text"),
                        resultSet.getBigDecimal("amount_delta"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "occurred_at", java.time.LocalDateTime.class)),
                        resultSet.getString("operator_id")))
                .list();
    }

    @Override
    public java.util.List<CompletionRecord> findCompletionsOfOrder(String orderBusinessId) {
        return jdbc.sql("""
                        SELECT fact_business_id, order_business_id, fact_type,
                               fulfillment_fingerprint, occurred_at, operator_id
                        FROM orders_completion_facts
                        WHERE order_business_id = :id AND is_valid = 1
                        ORDER BY occurred_at, fact_business_id
                        """)
                .param("id", orderBusinessId)
                .query((resultSet, rowNumber) -> new CompletionRecord(
                        resultSet.getString("fact_business_id"),
                        resultSet.getString("order_business_id"),
                        resultSet.getString("fact_type"),
                        resultSet.getString("fulfillment_fingerprint"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "occurred_at", java.time.LocalDateTime.class)),
                        resultSet.getString("operator_id")))
                .list();
    }

    @Override
    public void updateShipmentItemQuantity(String shipmentItemBusinessId, int quantity) {
        jdbc.sql("""
                        UPDATE orders_shipment_items SET quantity = :quantity
                        WHERE shipment_item_business_id = :id
                        """)
                .param("quantity", quantity)
                .param("id", shipmentItemBusinessId)
                .update();
    }

    @Override
    public void updateGroupFulfillment(
            String agreementGroupBusinessId, int fulfilledQuantity, String status) {
        jdbc.sql("""
                        UPDATE orders_agreement_groups
                        SET fulfilled_quantity = :quantity, status = :status,
                            row_version = row_version + 1
                        WHERE agreement_group_business_id = :id
                        """)
                .param("quantity", fulfilledQuantity)
                .param("status", status)
                .param("id", agreementGroupBusinessId)
                .update();
    }

    @Override
    public void lockOrder(String orderBusinessId) {
        jdbc.sql("SELECT order_business_id FROM orders_orders WHERE order_business_id = :id FOR UPDATE")
                .param("id", orderBusinessId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("订单不存在：" + orderBusinessId));
    }

    @Override
    public void updateOrderDeliveryFulfilled(
            String orderBusinessId, boolean deliveryFulfilled, boolean paymentFulfilled,
            long expectedContentVersion) {
        jdbc.sql("""
                        UPDATE orders_orders
                        SET delivery_fulfilled = :delivery, payment_fulfilled = :payment,
                            status = CASE WHEN status IN ('COMPLETED', 'CANCELLED') THEN status
                                          WHEN :delivery OR status = 'IN_FULFILLMENT' THEN 'IN_FULFILLMENT'
                                          ELSE status END,
                            content_version = content_version + 1, row_version = row_version + 1,
                            updated_at = UTC_TIMESTAMP(6)
                        WHERE order_business_id = :id
                        """)
                .param("delivery", deliveryFulfilled)
                .param("payment", paymentFulfilled)
                .param("id", orderBusinessId)
                .update();
    }

    @Override
    public java.math.BigDecimal receivedAmountOf(String orderBusinessId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(
                                   CASE WHEN link_type = 'RECEIPT' THEN linked_amount
                                        WHEN link_type = 'RECEIPT_REVERSAL' THEN -linked_amount
                                        ELSE 0 END), 0)
                        FROM orders_payment_link_facts
                        WHERE order_business_id = :id AND is_valid = 1
                        """)
                .param("id", orderBusinessId)
                .query(java.math.BigDecimal.class)
                .single();
    }

    @Override
    public java.math.BigDecimal refundedAmountOf(String orderBusinessId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(
                                   CASE WHEN link_type = 'REFUND' THEN linked_amount
                                        WHEN link_type = 'REFUND_REVERSAL' THEN -linked_amount
                                        ELSE 0 END), 0)
                        FROM orders_payment_link_facts
                        WHERE order_business_id = :id AND is_valid = 1
                        """)
                .param("id", orderBusinessId)
                .query(java.math.BigDecimal.class)
                .single();
    }

    @Override
    public java.math.BigDecimal netReceivedAmount(String orderBusinessId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(
                                   CASE WHEN link_type IN ('RECEIPT', 'REFUND_REVERSAL') THEN linked_amount
                                        WHEN link_type IN ('REFUND', 'RECEIPT_REVERSAL') THEN -linked_amount
                                        ELSE 0 END), 0)
                        FROM orders_payment_link_facts
                        WHERE order_business_id = :id AND is_valid = 1
                        """)
                .param("id", orderBusinessId)
                .query(java.math.BigDecimal.class)
                .single();
    }

    @Override
    public void insertPaymentLink(PaymentLinkRecord record) {
        jdbc.sql("""
                        INSERT INTO orders_payment_link_facts (
                            fact_business_id, order_business_id, finance_cash_fact_business_id,
                            link_type, linked_amount, reverses_fact_business_id,
                            corrects_fact_business_id, occurred_at, operator_id, is_valid)
                        VALUES (:id, :orderId, :cashFactId, :type, :amount, :reversesId,
                                :correctsId, :occurredAt, :operatorId, 1)
                        """)
                .param("id", record.factBusinessId())
                .param("orderId", record.orderBusinessId())
                .param("cashFactId", record.financeCashFactBusinessId())
                .param("type", record.linkType())
                .param("amount", record.linkedAmount())
                .param("reversesId", record.reversesFactBusinessId())
                .param("correctsId", record.correctsFactBusinessId())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public Optional<PaymentLinkRecord> findPaymentLink(String factBusinessId) {
        return jdbc.sql("""
                        SELECT fact_business_id, order_business_id, finance_cash_fact_business_id,
                               link_type, linked_amount, reverses_fact_business_id,
                               corrects_fact_business_id, occurred_at, operator_id
                        FROM orders_payment_link_facts WHERE fact_business_id = :id
                        """)
                .param("id", factBusinessId)
                .query(JdbcOrderRepository::mapPaymentLink)
                .optional();
    }

    @Override
    public Optional<PaymentLinkRecord> findPaymentLinkByCashFact(String financeCashFactBusinessId) {
        return jdbc.sql("""
                        SELECT fact_business_id, order_business_id, finance_cash_fact_business_id,
                               link_type, linked_amount, reverses_fact_business_id,
                               corrects_fact_business_id, occurred_at, operator_id
                        FROM orders_payment_link_facts
                        WHERE finance_cash_fact_business_id = :id
                          AND reverses_fact_business_id IS NULL AND is_valid = 1
                        """)
                .param("id", financeCashFactBusinessId)
                .query(JdbcOrderRepository::mapPaymentLink)
                .optional();
    }

    @Override
    public Optional<PaymentLinkRecord> findReversalOf(String originalFactBusinessId) {
        return jdbc.sql("""
                        SELECT fact_business_id, order_business_id, finance_cash_fact_business_id,
                               link_type, linked_amount, reverses_fact_business_id,
                               corrects_fact_business_id, occurred_at, operator_id
                        FROM orders_payment_link_facts
                        WHERE reverses_fact_business_id = :id AND is_valid = 1
                        """)
                .param("id", originalFactBusinessId)
                .query(JdbcOrderRepository::mapPaymentLink)
                .optional();
    }

    @Override
    public Optional<RefundObligationRecord> findRefundObligation(String orderBusinessId) {
        return jdbc.sql("""
                        SELECT refund_business_id, order_business_id, decided_amount, paid_amount,
                               status, row_version
                        FROM orders_refund_obligations WHERE order_business_id = :id
                        """)
                .param("id", orderBusinessId)
                .query((resultSet, rowNumber) -> new RefundObligationRecord(
                        resultSet.getString("refund_business_id"),
                        resultSet.getString("order_business_id"),
                        resultSet.getBigDecimal("decided_amount"),
                        resultSet.getBigDecimal("paid_amount"),
                        resultSet.getString("status"),
                        resultSet.getLong("row_version")))
                .optional();
    }

    @Override
    public void lockRefundObligation(String orderBusinessId) {
        jdbc.sql("""
                        SELECT refund_business_id FROM orders_refund_obligations
                        WHERE order_business_id = :id FOR UPDATE
                        """)
                .param("id", orderBusinessId)
                .query(String.class)
                .optional();
    }

    @Override
    public void insertRefundObligation(RefundObligationRecord record) {
        jdbc.sql("""
                        INSERT INTO orders_refund_obligations (
                            refund_business_id, order_business_id, decided_amount, paid_amount,
                            status, row_version, created_at, updated_at)
                        VALUES (:id, :orderId, :decided, :paid, :status, 1,
                                UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                        """)
                .param("id", record.refundBusinessId())
                .param("orderId", record.orderBusinessId())
                .param("decided", record.decidedAmount())
                .param("paid", record.paidAmount())
                .param("status", record.status())
                .update();
    }

    @Override
    public void updateRefundObligation(
            String refundBusinessId, java.math.BigDecimal decidedAmount,
            java.math.BigDecimal paidAmount, String status) {
        jdbc.sql("""
                        UPDATE orders_refund_obligations
                        SET decided_amount = :decided, paid_amount = :paid, status = :status,
                            row_version = row_version + 1, updated_at = UTC_TIMESTAMP(6)
                        WHERE refund_business_id = :id
                        """)
                .param("decided", decidedAmount)
                .param("paid", paidAmount)
                .param("status", status)
                .param("id", refundBusinessId)
                .update();
    }

    @Override
    public void insertCompletionFact(CompletionRecord record) {
        jdbc.sql("""
                        INSERT INTO orders_completion_facts (
                            fact_business_id, order_business_id, fact_type,
                            fulfillment_fingerprint, occurred_at, operator_id, is_valid)
                        VALUES (:id, :orderId, :type, :fingerprint, :occurredAt, :operatorId, 1)
                        """)
                .param("id", record.factBusinessId())
                .param("orderId", record.orderBusinessId())
                .param("type", record.factType())
                .param("fingerprint", record.fulfillmentFingerprint())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public Optional<CompletionRecord> findCompletion(String orderBusinessId) {
        return jdbc.sql("""
                        SELECT fact_business_id, order_business_id, fact_type,
                               fulfillment_fingerprint, occurred_at, operator_id
                        FROM orders_completion_facts
                        WHERE order_business_id = :id AND is_valid = 1 AND fact_type = 'COMPLETED'
                        """)
                .param("id", orderBusinessId)
                .query((resultSet, rowNumber) -> new CompletionRecord(
                        resultSet.getString("fact_business_id"),
                        resultSet.getString("order_business_id"),
                        resultSet.getString("fact_type"),
                        resultSet.getString("fulfillment_fingerprint"),
                        JdbcTimes.toInstant(resultSet.getObject("occurred_at", java.time.LocalDateTime.class)),
                        resultSet.getString("operator_id")))
                .optional();
    }

    @Override
    public void markOrderCompleted(String orderBusinessId) {
        jdbc.sql("""
                        UPDATE orders_orders
                        SET status = 'COMPLETED', content_version = content_version + 1,
                            row_version = row_version + 1, updated_at = UTC_TIMESTAMP(6)
                        WHERE order_business_id = :id
                        """)
                .param("id", orderBusinessId)
                .update();
    }

    @Override
    public void insertLegacyIntake(LegacyIntakeRecord record) {
        jdbc.sql("""
                        INSERT INTO orders_legacy_intakes (
                            intake_business_id, order_business_id, original_order_reference,
                            note, occurred_at, operator_id)
                        VALUES (:id, :orderId, :reference, :note, :occurredAt, :operatorId)
                        """)
                .param("id", record.intakeBusinessId())
                .param("orderId", record.orderBusinessId())
                .param("reference", record.originalOrderReference())
                .param("note", record.note())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public void insertLegacyIntakeItem(
            String intakeBusinessId, String orderItemBusinessId, int intakeQuantity, String note) {
        jdbc.sql("""
                        INSERT INTO orders_legacy_intake_items (
                            intake_business_id, order_item_business_id, intake_quantity, note)
                        VALUES (:intakeId, :itemId, :quantity, :note)
                        """)
                .param("intakeId", intakeBusinessId)
                .param("itemId", orderItemBusinessId)
                .param("quantity", intakeQuantity)
                .param("note", note)
                .update();
    }

    @Override
    public void insertLegacyStateLine(LegacyStateLineRecord record) {
        jdbc.sql("""
                        INSERT INTO orders_legacy_state_lines (
                            intake_business_id, order_item_business_id, stage_code, quality_code,
                            disposition_code, physical_destination, rework_process_code, quantity,
                            reason_text)
                        VALUES (:intakeId, :itemId, :stageCode, :qualityCode, :dispositionCode,
                                :destination, :reworkProcess, :quantity, :reasonText)
                        """)
                .param("intakeId", record.intakeBusinessId())
                .param("itemId", record.orderItemBusinessId())
                .param("stageCode", record.stageCode())
                .param("qualityCode", record.qualityCode())
                .param("dispositionCode", record.dispositionCode())
                .param("destination", record.physicalDestination())
                .param("reworkProcess", record.reworkProcessCode())
                .param("quantity", record.quantity())
                .param("reasonText", record.reasonText())
                .update();
    }

    @Override
    public List<OrderRecord> searchOrders(String keyword, String status, int offset, int limit) {
        return jdbc.sql("""
                        SELECT o.order_business_id, o.order_no, o.origin, o.customer_business_id,
                               o.customer_snapshot_ref, o.order_date, o.expected_delivery_date,
                               o.order_note, o.status, o.current_receivable_amount,
                               COALESCE(p.received_amount, 0) - COALESCE(p.refunded_amount, 0)
                                   AS net_receipts,
                               o.currency_code, o.delivery_fulfilled, o.payment_fulfilled,
                               o.content_version, o.row_version, o.created_at, o.created_by,
                               o.updated_at
                        FROM orders_orders o
                        LEFT JOIN orders_fulfillment_projections p
                               ON p.order_business_id = o.order_business_id
                        WHERE (:keyword = '' OR o.order_no LIKE CONCAT('%', :keyword, '%'))
                          AND (:status = '' OR o.status = :status)
                        ORDER BY o.created_at DESC, o.order_business_id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("keyword", keyword == null ? "" : keyword)
                .param("status", status == null ? "" : status)
                .param("limit", limit)
                .param("offset", offset)
                .query(JdbcOrderRepository::mapOrder)
                .list();
    }

    @Override
    public List<OrderSummaryRecord> searchOrderSummaries(
            String keyword, String status, int offset, int limit) {
        return jdbc.sql("""
                        SELECT o.order_business_id, o.order_no, o.origin, o.customer_business_id,
                               o.customer_snapshot_ref, o.order_date, o.expected_delivery_date,
                               o.order_note, o.status, o.current_receivable_amount,
                               COALESCE(p.received_amount, 0) - COALESCE(p.refunded_amount, 0)
                                   AS net_receipts,
                               o.currency_code, o.delivery_fulfilled, o.payment_fulfilled,
                               o.content_version, o.row_version, o.created_at, o.created_by,
                               o.updated_at,
                               COALESCE(cs.name, '') AS customer_name,
                               COALESCE((
                                   SELECT GROUP_CONCAT(DISTINCT
                                               COALESCE(
                                                   JSON_UNQUOTE(JSON_EXTRACT(ps.base_payload_json, '$.name')),
                                                   pp.name)
                                               ORDER BY COALESCE(
                                                   JSON_UNQUOTE(JSON_EXTRACT(ps.base_payload_json, '$.name')),
                                                   pp.name)
                                               SEPARATOR '、')
                                   FROM orders_items oi
                                   LEFT JOIN product_snapshots ps
                                          ON ps.snapshot_business_id = oi.product_snapshot_ref
                                   LEFT JOIN product_products pp
                                          ON pp.product_business_id = oi.product_business_id
                                   WHERE oi.order_business_id = o.order_business_id
                               ), '') AS product_names
                        FROM orders_orders o
                        LEFT JOIN orders_fulfillment_projections p
                               ON p.order_business_id = o.order_business_id
                        LEFT JOIN customer_snapshots cs
                               ON cs.snapshot_business_id = o.customer_snapshot_ref
                        WHERE (:keyword = '' OR o.order_no LIKE CONCAT('%', :keyword, '%')
                               OR cs.name LIKE CONCAT('%', :keyword, '%')
                               OR EXISTS (
                                   SELECT 1 FROM orders_items oi2
                                   LEFT JOIN product_snapshots ps2
                                          ON ps2.snapshot_business_id = oi2.product_snapshot_ref
                                   LEFT JOIN product_products pp2
                                          ON pp2.product_business_id = oi2.product_business_id
                                   WHERE oi2.order_business_id = o.order_business_id
                                     AND COALESCE(
                                           JSON_UNQUOTE(JSON_EXTRACT(ps2.base_payload_json, '$.name')),
                                           pp2.name) LIKE CONCAT('%', :keyword, '%')
                               ))
                          AND (:status = '' OR o.status = :status)
                        ORDER BY o.created_at DESC, o.order_business_id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("keyword", keyword == null ? "" : keyword)
                .param("status", status == null ? "" : status)
                .param("limit", limit)
                .param("offset", offset)
                .query((resultSet, rowNumber) -> new OrderSummaryRecord(
                        mapOrder(resultSet, rowNumber),
                        resultSet.getString("customer_name"),
                        resultSet.getString("product_names")))
                .list();
    }

    @Override
    public List<OrderRecord> findOrdersChangedAfter(
            Instant updatedAfter, String afterOrderBusinessId, int limit) {
        return jdbc.sql("""
                        SELECT o.order_business_id, o.order_no, o.origin, o.customer_business_id,
                               o.customer_snapshot_ref, o.order_date, o.expected_delivery_date,
                               o.order_note, o.status, o.current_receivable_amount,
                               COALESCE(p.received_amount, 0) - COALESCE(p.refunded_amount, 0)
                                   AS net_receipts,
                               o.currency_code, o.delivery_fulfilled, o.payment_fulfilled,
                               o.content_version, o.row_version, o.created_at, o.created_by,
                               o.updated_at
                        FROM orders_orders o
                        LEFT JOIN orders_fulfillment_projections p
                               ON p.order_business_id = o.order_business_id
                        WHERE o.updated_at > :after
                           OR (o.updated_at = :after AND o.order_business_id > :afterId)
                        ORDER BY o.updated_at, o.order_business_id
                        LIMIT :limit
                        """)
                .param("after", JdbcTimes.toUtc(updatedAfter))
                .param("afterId", afterOrderBusinessId == null ? "" : afterOrderBusinessId)
                .param("limit", limit)
                .query(JdbcOrderRepository::mapOrder)
                .list();
    }

    private static PaymentLinkRecord mapPaymentLink(
            java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new PaymentLinkRecord(
                resultSet.getString("fact_business_id"),
                resultSet.getString("order_business_id"),
                resultSet.getString("finance_cash_fact_business_id"),
                resultSet.getString("link_type"),
                resultSet.getBigDecimal("linked_amount"),
                resultSet.getString("reverses_fact_business_id"),
                resultSet.getString("corrects_fact_business_id"),
                JdbcTimes.toInstant(resultSet.getObject("occurred_at", java.time.LocalDateTime.class)),
                resultSet.getString("operator_id"));
    }

    private static ShipmentItemRecord mapShipmentItem(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        return new ShipmentItemRecord(
                resultSet.getString("shipment_item_business_id"),
                resultSet.getString("shipment_business_id"),
                resultSet.getString("agreement_group_business_id"),
                resultSet.getString("agreement_version_business_id"),
                resultSet.getString("production_source_business_id"),
                resultSet.getString("production_consumption_fact_business_id"),
                resultSet.getInt("quantity"));
    }

    @Override
    public Optional<OrderRecord> findOrder(String orderBusinessId) {
        return jdbc.sql("""
                        SELECT o.order_business_id, o.order_no, o.origin, o.customer_business_id,
                               o.customer_snapshot_ref, o.order_date, o.expected_delivery_date,
                               o.order_note, o.status, o.current_receivable_amount,
                               COALESCE(p.received_amount, 0) - COALESCE(p.refunded_amount, 0)
                                   AS net_receipts,
                               o.currency_code, o.delivery_fulfilled, o.payment_fulfilled,
                               o.content_version, o.row_version, o.created_at, o.created_by,
                               o.updated_at
                        FROM orders_orders o
                        LEFT JOIN orders_fulfillment_projections p
                               ON p.order_business_id = o.order_business_id
                        WHERE o.order_business_id = :id
                        """)
                .param("id", orderBusinessId)
                .query(JdbcOrderRepository::mapOrder)
                .optional();
    }

    private static OrderRecord mapOrder(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        return new OrderRecord(
                resultSet.getString("order_business_id"),
                resultSet.getString("order_no"),
                resultSet.getString("origin"),
                resultSet.getString("customer_business_id"),
                resultSet.getString("customer_snapshot_ref"),
                resultSet.getObject("order_date", LocalDate.class),
                resultSet.getObject("expected_delivery_date", LocalDate.class),
                resultSet.getString("order_note"),
                resultSet.getString("status"),
                resultSet.getBigDecimal("current_receivable_amount"),
                resultSet.getBigDecimal("net_receipts"),
                resultSet.getString("currency_code"),
                resultSet.getBoolean("delivery_fulfilled"),
                resultSet.getBoolean("payment_fulfilled"),
                resultSet.getLong("content_version"),
                resultSet.getLong("row_version"),
                JdbcTimes.toInstant(resultSet.getObject("created_at", java.time.LocalDateTime.class)),
                resultSet.getString("created_by"),
                JdbcTimes.toInstant(resultSet.getObject("updated_at", java.time.LocalDateTime.class)));
    }

    @Override
    public List<ItemRecord> findItems(String orderBusinessId) {
        return jdbc.sql("""
                        SELECT order_item_business_id, order_business_id, item_no, product_business_id,
                               product_snapshot_ref, ordered_quantity, active_quantity, status, row_version
                        FROM orders_items WHERE order_business_id = :id ORDER BY item_no
                        """)
                .param("id", orderBusinessId)
                .query((resultSet, rowNumber) -> new ItemRecord(
                        resultSet.getString("order_item_business_id"),
                        resultSet.getString("order_business_id"),
                        resultSet.getInt("item_no"),
                        resultSet.getString("product_business_id"),
                        resultSet.getString("product_snapshot_ref"),
                        resultSet.getInt("ordered_quantity"),
                        resultSet.getInt("active_quantity"),
                        resultSet.getString("status"),
                        resultSet.getLong("row_version")))
                .list();
    }

    @Override
    public Optional<AgreementGroupRecord> findGroupById(String agreementGroupBusinessId) {
        return jdbc.sql("""
                        SELECT agreement_group_business_id, order_item_business_id, active_version_no,
                               active_quantity, fulfilled_quantity, status, row_version
                        FROM orders_agreement_groups WHERE agreement_group_business_id = :id
                        """)
                .param("id", agreementGroupBusinessId)
                .query((resultSet, rowNumber) -> new AgreementGroupRecord(
                        resultSet.getString("agreement_group_business_id"),
                        resultSet.getString("order_item_business_id"),
                        resultSet.getInt("active_version_no"),
                        resultSet.getInt("active_quantity"),
                        resultSet.getInt("fulfilled_quantity"),
                        resultSet.getString("status"),
                        resultSet.getLong("row_version")))
                .optional();
    }

    @Override
    public void lockGroup(String agreementGroupBusinessId) {
        jdbc.sql("""
                        SELECT agreement_group_business_id FROM orders_agreement_groups
                        WHERE agreement_group_business_id = :id FOR UPDATE
                        """)
                .param("id", agreementGroupBusinessId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "约定组不存在：" + agreementGroupBusinessId));
    }

    @Override
    public Optional<AgreementGroupRecord> findGroup(String orderItemBusinessId) {
        return jdbc.sql("""
                        SELECT agreement_group_business_id, order_item_business_id, active_version_no,
                               active_quantity, fulfilled_quantity, status, row_version
                        FROM orders_agreement_groups WHERE order_item_business_id = :id
                        """)
                .param("id", orderItemBusinessId)
                .query((resultSet, rowNumber) -> new AgreementGroupRecord(
                        resultSet.getString("agreement_group_business_id"),
                        resultSet.getString("order_item_business_id"),
                        resultSet.getInt("active_version_no"),
                        resultSet.getInt("active_quantity"),
                        resultSet.getInt("fulfilled_quantity"),
                        resultSet.getString("status"),
                        resultSet.getLong("row_version")))
                .optional();
    }

    @Override
    public Optional<AgreementVersionRecord> findActiveVersion(String groupBusinessId, int versionNo) {
        return jdbc.sql("""
                        SELECT agreement_version_business_id, group_business_id, version_no,
                               scheme_snapshot_ref, target_process, unit_price, quantity, glue_source,
                               color_paste_source, bag_source, decoration_source, production_basis_json,
                               production_basis_fingerprint, recipient_payload_json,
                               expected_cost_payload_json, effective_fact_business_id, content_fingerprint,
                               created_at
                        FROM orders_agreement_versions
                        WHERE group_business_id = :groupId AND version_no = :versionNo
                        """)
                .param("groupId", groupBusinessId)
                .param("versionNo", versionNo)
                .query((resultSet, rowNumber) -> new AgreementVersionRecord(
                        resultSet.getString("agreement_version_business_id"),
                        resultSet.getString("group_business_id"),
                        resultSet.getInt("version_no"),
                        resultSet.getString("scheme_snapshot_ref"),
                        resultSet.getString("target_process"),
                        resultSet.getBigDecimal("unit_price"),
                        resultSet.getInt("quantity"),
                        resultSet.getString("glue_source"),
                        resultSet.getString("color_paste_source"),
                        resultSet.getString("bag_source"),
                        resultSet.getString("decoration_source"),
                        resultSet.getString("production_basis_json"),
                        resultSet.getString("production_basis_fingerprint"),
                        resultSet.getString("recipient_payload_json"),
                        resultSet.getString("expected_cost_payload_json"),
                        resultSet.getString("effective_fact_business_id"),
                        resultSet.getString("content_fingerprint"),
                        JdbcTimes.toInstant(resultSet.getObject("created_at", java.time.LocalDateTime.class))))
                .optional();
    }

    @Override
    public List<AgreementProcessRecord> findProcesses(String agreementVersionBusinessId) {
        return jdbc.sql("""
                        SELECT agreement_version_business_id, process_code, standard_minutes, labor_cost,
                               material_cost, other_cost, source_terms_json
                        FROM orders_agreement_processes
                        WHERE agreement_version_business_id = :id ORDER BY process_code
                        """)
                .param("id", agreementVersionBusinessId)
                .query((resultSet, rowNumber) -> new AgreementProcessRecord(
                        resultSet.getString("agreement_version_business_id"),
                        resultSet.getString("process_code"),
                        resultSet.getInt("standard_minutes"),
                        resultSet.getBigDecimal("labor_cost"),
                        resultSet.getBigDecimal("material_cost"),
                        resultSet.getBigDecimal("other_cost"),
                        resultSet.getString("source_terms_json")))
                .list();
    }

    @Override
    public Optional<ProjectionRecord> findProjection(String orderBusinessId) {
        return jdbc.sql("""
                        SELECT order_business_id, required_quantity, shipped_quantity, current_receivable,
                               received_amount, refunded_amount, delivery_fulfilled, payment_fulfilled,
                               blocking_reason_codes, row_version
                        FROM orders_fulfillment_projections WHERE order_business_id = :id
                        """)
                .param("id", orderBusinessId)
                .query((resultSet, rowNumber) -> new ProjectionRecord(
                        resultSet.getString("order_business_id"),
                        resultSet.getInt("required_quantity"),
                        resultSet.getInt("shipped_quantity"),
                        resultSet.getBigDecimal("current_receivable"),
                        resultSet.getBigDecimal("received_amount"),
                        resultSet.getBigDecimal("refunded_amount"),
                        resultSet.getBoolean("delivery_fulfilled"),
                        resultSet.getBoolean("payment_fulfilled"),
                        resultSet.getString("blocking_reason_codes"),
                        resultSet.getLong("row_version")))
                .optional();
    }
}
