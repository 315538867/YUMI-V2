-- 售后退回、退回制品池重入、原样退还与池处置（基线 12 §13.3、11 §7.11—§7.12）

ALTER TABLE after_sales_obligations
    ADD COLUMN order_item_business_id VARCHAR(64) NULL AFTER case_business_id,
    ADD COLUMN fulfilled_quantity INT NOT NULL DEFAULT 0 AFTER target_amount,
    ADD COLUMN fulfilled_amount DECIMAL(19,2) NOT NULL DEFAULT 0 AFTER fulfilled_quantity,
    ADD CONSTRAINT ck_obligation_fulfilled CHECK (
        fulfilled_quantity >= 0 AND fulfilled_amount >= 0);

CREATE TABLE after_sales_return_receipt_facts (
    fact_business_id VARCHAR(64) NOT NULL,
    obligation_business_id VARCHAR(64) NOT NULL,
    received_total_quantity INT NOT NULL,
    condition_code VARCHAR(32) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (fact_business_id),
    KEY ix_receipt_obligation (obligation_business_id, received_at),
    CONSTRAINT ck_receipt_quantity CHECK (received_total_quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE after_sales_return_receipt_items (
    receipt_item_business_id VARCHAR(64) NOT NULL,
    receipt_fact_business_id VARCHAR(64) NOT NULL,
    obligation_business_id VARCHAR(64) NOT NULL,
    prior_delivery_type VARCHAR(32) NOT NULL,
    prior_delivery_business_id VARCHAR(64) NOT NULL,
    prior_delivery_item_business_id VARCHAR(64) NOT NULL,
    prior_delivery_source_type VARCHAR(32) NOT NULL,
    prior_production_source_business_id VARCHAR(64) NULL,
    prior_returned_source_business_id VARCHAR(64) NULL,
    physical_root_source_business_id VARCHAR(64) NOT NULL,
    prior_consumption_fact_business_id VARCHAR(64) NOT NULL,
    production_returned_source_business_id VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    PRIMARY KEY (receipt_item_business_id),
    UNIQUE KEY uk_receipt_item_returned_source (production_returned_source_business_id),
    KEY ix_receipt_item_prior (prior_delivery_item_business_id),
    CONSTRAINT ck_receipt_item_delivery_type CHECK (
        prior_delivery_type IN ('SHIPMENT', 'AFTER_SALES_DELIVERY')),
    CONSTRAINT ck_receipt_item_source_type CHECK (
        prior_delivery_source_type IN ('PRODUCTION_SOURCE', 'RETURNED_PRODUCT_POOL')),
    CONSTRAINT ck_receipt_item_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE after_sales_delivery_facts (
    fact_business_id VARCHAR(64) NOT NULL,
    obligation_business_id VARCHAR(64) NOT NULL,
    order_item_business_id VARCHAR(64) NOT NULL,
    delivery_type VARCHAR(32) NOT NULL,
    delivered_at DATETIME(6) NOT NULL,
    recipient_payload_json JSON NOT NULL,
    tracking_no VARCHAR(64) NULL,
    operator_id VARCHAR(64) NOT NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (fact_business_id),
    KEY ix_after_sales_delivery_item (order_item_business_id, delivered_at),
    CONSTRAINT ck_after_sales_delivery_type CHECK (
        delivery_type IN ('RETURN_TO_CUSTOMER', 'REPAIR_DELIVERY', 'REPLACEMENT_DELIVERY'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE after_sales_delivery_items (
    delivery_item_business_id VARCHAR(64) NOT NULL,
    delivery_fact_business_id VARCHAR(64) NOT NULL,
    delivery_source_type VARCHAR(32) NOT NULL,
    production_source_business_id VARCHAR(64) NULL,
    production_returned_source_business_id VARCHAR(64) NULL,
    physical_root_source_business_id VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    production_consumption_fact_business_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (delivery_item_business_id),
    UNIQUE KEY uk_after_sales_delivery_consumption (production_consumption_fact_business_id),
    CONSTRAINT ck_after_sales_delivery_source CHECK (
        delivery_source_type IN ('PRODUCTION_SOURCE', 'RETURNED_PRODUCT_POOL')),
    CONSTRAINT ck_after_sales_delivery_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE after_sales_return_disposition_facts (
    fact_business_id VARCHAR(64) NOT NULL,
    obligation_business_id VARCHAR(64) NOT NULL,
    return_receipt_item_business_id VARCHAR(64) NOT NULL,
    production_returned_source_business_id VARCHAR(64) NOT NULL,
    disposition_type VARCHAR(32) NOT NULL,
    physical_destination VARCHAR(32) NOT NULL,
    production_terminal_exit_business_id VARCHAR(64) NULL,
    quantity INT NOT NULL,
    production_disposition_fact_business_id VARCHAR(64) NOT NULL,
    production_consumption_fact_business_id VARCHAR(64) NULL,
    production_offline_transfer_fact_business_id VARCHAR(64) NULL,
    reason_text VARCHAR(500) NOT NULL,
    business_date DATE NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (fact_business_id),
    KEY ix_orders_disposition_item (return_receipt_item_business_id),
    CONSTRAINT ck_orders_disposition_type CHECK (
        disposition_type IN ('RETURN_TO_CUSTOMER', 'SCRAPPED', 'RELEASE_FROM_OBLIGATION')),
    CONSTRAINT ck_orders_disposition_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE after_sales_production_links (
    link_business_id VARCHAR(64) NOT NULL,
    obligation_business_id VARCHAR(64) NOT NULL,
    return_receipt_item_business_id VARCHAR(64) NULL,
    production_demand_business_id VARCHAR(64) NOT NULL,
    production_repair_source_business_id VARCHAR(64) NULL,
    demand_type VARCHAR(32) NOT NULL,
    link_type VARCHAR(32) NOT NULL,
    PRIMARY KEY (link_business_id),
    UNIQUE KEY uk_after_sales_demand (production_demand_business_id),
    CONSTRAINT ck_after_sales_link_demand CHECK (
        demand_type IN ('NEW_PRODUCT', 'RETURNED_PRODUCT_REPAIR')),
    CONSTRAINT ck_after_sales_link_type CHECK (link_type IN ('REPAIR', 'REPLACEMENT'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_return_reentry_facts (
    fact_business_id VARCHAR(64) NOT NULL,
    returned_source_business_id VARCHAR(64) NOT NULL,
    after_sales_return_receipt_item_business_id VARCHAR(64) NOT NULL,
    physical_root_source_business_id VARCHAR(64) NOT NULL,
    prior_delivery_source_type VARCHAR(32) NOT NULL,
    prior_production_source_business_id VARCHAR(64) NULL,
    prior_returned_source_business_id VARCHAR(64) NULL,
    prior_consumption_fact_business_id VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (fact_business_id),
    UNIQUE KEY uk_return_reentry_item (after_sales_return_receipt_item_business_id),
    CONSTRAINT ck_return_reentry_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_returned_pool_consumption_facts (
    consumption_fact_business_id VARCHAR(64) NOT NULL,
    returned_source_business_id VARCHAR(64) NOT NULL,
    physical_root_source_business_id VARCHAR(64) NOT NULL,
    source_module VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_business_id VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (consumption_fact_business_id),
    KEY ix_pool_consumption_source (returned_source_business_id, is_valid),
    CONSTRAINT ck_pool_consumption_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_returned_product_disposition_facts (
    fact_business_id VARCHAR(64) NOT NULL,
    returned_source_business_id VARCHAR(64) NOT NULL,
    physical_root_source_business_id VARCHAR(64) NOT NULL,
    disposition_type VARCHAR(32) NOT NULL,
    physical_destination VARCHAR(32) NOT NULL,
    terminal_exit_business_id VARCHAR(64) NULL,
    quantity INT NOT NULL,
    consumption_fact_business_id VARCHAR(64) NULL,
    offline_transfer_fact_business_id VARCHAR(64) NULL,
    reason_code VARCHAR(32) NOT NULL,
    reason_text VARCHAR(500) NOT NULL,
    business_date DATE NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (fact_business_id),
    KEY ix_pool_disposition_source (returned_source_business_id, occurred_at),
    CONSTRAINT ck_pool_disposition_type CHECK (
        disposition_type IN ('RETURN_TO_CUSTOMER', 'SCRAPPED', 'RELEASE_FROM_OBLIGATION')),
    CONSTRAINT ck_pool_disposition_destination CHECK (
        physical_destination IN ('CONSUMED', 'DESTROYED', 'TRANSFERRED_OFFLINE_INVENTORY')),
    CONSTRAINT ck_pool_disposition_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_returned_pool_transfers (
    transfer_fact_business_id VARCHAR(64) NOT NULL,
    terminal_exit_business_id VARCHAR(64) NOT NULL,
    returned_source_business_id VARCHAR(64) NOT NULL,
    physical_root_source_business_id VARCHAR(64) NOT NULL,
    disposition_fact_business_id VARCHAR(64) NOT NULL,
    offline_inventory_name VARCHAR(120) NOT NULL,
    offline_inventory_note VARCHAR(500) NULL,
    handed_over_by VARCHAR(80) NOT NULL,
    received_by VARCHAR(80) NOT NULL,
    transferred_at DATETIME(6) NOT NULL,
    quantity INT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (transfer_fact_business_id),
    UNIQUE KEY uk_pool_transfer_terminal (terminal_exit_business_id),
    CONSTRAINT ck_pool_transfer_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
