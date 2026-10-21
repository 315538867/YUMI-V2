-- 发货事实、来源消费引用与发货更正（基线 12 §8.3）

CREATE TABLE orders_shipments (
    shipment_business_id VARCHAR(64) NOT NULL,
    shipment_no VARCHAR(32) NOT NULL,
    order_business_id VARCHAR(64) NOT NULL,
    shipped_at DATETIME(6) NOT NULL,
    recipient_payload_json JSON NOT NULL,
    tracking_no VARCHAR(64) NULL,
    operator_id VARCHAR(64) NOT NULL,
    command_execution_id VARCHAR(64) NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (shipment_business_id),
    UNIQUE KEY uk_shipment_no (shipment_no),
    KEY ix_shipment_order (order_business_id, shipped_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE orders_shipment_items (
    shipment_item_business_id VARCHAR(64) NOT NULL,
    shipment_business_id VARCHAR(64) NOT NULL,
    agreement_group_business_id VARCHAR(64) NOT NULL,
    agreement_version_business_id VARCHAR(64) NOT NULL,
    production_source_business_id VARCHAR(64) NOT NULL,
    production_consumption_fact_business_id VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    refunded_quantity INT NOT NULL DEFAULT 0,
    PRIMARY KEY (shipment_item_business_id),
    UNIQUE KEY uk_shipment_item_source (production_consumption_fact_business_id),
    KEY ix_shipment_item_shipment (shipment_business_id),
    CONSTRAINT ck_shipment_item_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE orders_shipment_source_links (
    id BIGINT NOT NULL AUTO_INCREMENT,
    shipment_business_id VARCHAR(64) NOT NULL,
    production_source_business_id VARCHAR(64) NOT NULL,
    production_consumption_fact_business_id VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_shipment_source (shipment_business_id, production_source_business_id),
    CONSTRAINT ck_shipment_link_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE orders_shipment_correction_facts (
    fact_business_id VARCHAR(64) NOT NULL,
    shipment_business_id VARCHAR(64) NOT NULL,
    shipment_item_business_id VARCHAR(64) NULL,
    correction_type VARCHAR(32) NOT NULL,
    quantity_delta INT NOT NULL,
    reason_text VARCHAR(500) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (fact_business_id),
    KEY ix_shipment_correction (shipment_business_id, occurred_at),
    CONSTRAINT ck_shipment_correction_type CHECK (
        correction_type IN ('QUANTITY_DECREASE', 'RETURN_RECEIPT')),
    CONSTRAINT ck_shipment_correction_delta CHECK (quantity_delta <> 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
