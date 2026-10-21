-- 订单确认：订单根、确认事实、订单项、交付约定组与不可变约定版本（基线 12 §8.1）

CREATE TABLE orders_orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_business_id VARCHAR(64) NOT NULL,
    order_no VARCHAR(32) NOT NULL,
    origin VARCHAR(32) NOT NULL,
    customer_business_id VARCHAR(64) NOT NULL,
    customer_snapshot_ref VARCHAR(64) NOT NULL,
    order_date DATE NOT NULL,
    expected_delivery_date DATE NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_receivable_amount DECIMAL(19,2) NOT NULL,
    currency_code CHAR(3) NOT NULL,
    delivery_fulfilled TINYINT(1) NOT NULL DEFAULT 0,
    payment_fulfilled TINYINT(1) NOT NULL DEFAULT 0,
    content_version BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_business_id (order_business_id),
    UNIQUE KEY uk_order_no (order_no),
    KEY ix_order_status_date (status, order_date),
    CONSTRAINT ck_order_status CHECK (
        status IN ('CONFIRMED', 'IN_FULFILLMENT', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_order_receivable CHECK (current_receivable_amount >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE orders_confirmation_facts (
    fact_business_id VARCHAR(64) NOT NULL,
    order_business_id VARCHAR(64) NOT NULL,
    confirmation_type VARCHAR(32) NOT NULL,
    total_quantity INT NOT NULL,
    total_amount DECIMAL(19,2) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    command_execution_id VARCHAR(64) NULL,
    PRIMARY KEY (fact_business_id),
    UNIQUE KEY uk_order_original_confirmation (order_business_id, confirmation_type),
    CONSTRAINT ck_confirmation_quantity CHECK (total_quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE orders_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_item_business_id VARCHAR(64) NOT NULL,
    order_business_id VARCHAR(64) NOT NULL,
    item_no INT NOT NULL,
    predecessor_order_item_business_id VARCHAR(64) NULL,
    created_by_change_business_id VARCHAR(64) NULL,
    product_business_id VARCHAR(64) NOT NULL,
    product_snapshot_ref VARCHAR(64) NOT NULL,
    ordered_quantity INT NOT NULL,
    active_quantity INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_item_business_id (order_item_business_id),
    UNIQUE KEY uk_order_item_no (order_business_id, item_no),
    KEY ix_order_item_order (order_business_id),
    CONSTRAINT ck_order_item_quantity CHECK (ordered_quantity > 0 AND active_quantity >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE orders_agreement_groups (
    agreement_group_business_id VARCHAR(64) NOT NULL,
    order_item_business_id VARCHAR(64) NOT NULL,
    active_version_no INT NOT NULL,
    active_quantity INT NOT NULL,
    fulfilled_quantity INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (agreement_group_business_id),
    UNIQUE KEY uk_agreement_group_item (order_item_business_id),
    CONSTRAINT ck_agreement_group_quantity CHECK (active_quantity > 0 AND fulfilled_quantity >= 0),
    CONSTRAINT ck_agreement_group_status CHECK (status IN ('ACTIVE', 'FULFILLED', 'CANCELLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE orders_agreement_versions (
    agreement_version_business_id VARCHAR(64) NOT NULL,
    group_business_id VARCHAR(64) NOT NULL,
    version_no INT NOT NULL,
    scheme_snapshot_ref VARCHAR(64) NOT NULL,
    target_process VARCHAR(32) NOT NULL,
    unit_price DECIMAL(19,2) NOT NULL,
    quantity INT NOT NULL,
    glue_source VARCHAR(32) NOT NULL,
    color_paste_source VARCHAR(32) NOT NULL,
    bag_source VARCHAR(32) NOT NULL,
    decoration_source VARCHAR(32) NOT NULL,
    production_basis_json JSON NOT NULL,
    production_basis_fingerprint CHAR(64) NOT NULL,
    recipient_payload_json JSON NOT NULL,
    expected_cost_payload_json JSON NULL,
    effective_fact_business_id VARCHAR(64) NOT NULL,
    content_fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (agreement_version_business_id),
    UNIQUE KEY uk_agreement_version_no (group_business_id, version_no),
    CONSTRAINT ck_agreement_version_quantity CHECK (quantity > 0),
    CONSTRAINT ck_agreement_version_price CHECK (unit_price >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE orders_agreement_processes (
    agreement_version_business_id VARCHAR(64) NOT NULL,
    process_code VARCHAR(32) NOT NULL,
    standard_minutes INT NOT NULL,
    labor_cost DECIMAL(19,4) NOT NULL,
    material_cost DECIMAL(19,4) NOT NULL,
    other_cost DECIMAL(19,4) NOT NULL,
    source_terms_json JSON NULL,
    PRIMARY KEY (agreement_version_business_id, process_code),
    CONSTRAINT ck_agreement_process_minutes CHECK (standard_minutes > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE orders_receivable_facts (
    fact_business_id VARCHAR(64) NOT NULL,
    order_business_id VARCHAR(64) NOT NULL,
    fact_type VARCHAR(32) NOT NULL,
    amount_delta DECIMAL(19,2) NOT NULL,
    currency_code CHAR(3) NOT NULL,
    source_change_business_id VARCHAR(64) NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    corrects_fact_business_id VARCHAR(64) NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (fact_business_id),
    KEY ix_receivable_order (order_business_id, is_valid),
    CONSTRAINT ck_receivable_type CHECK (fact_type IN ('ORIGINAL', 'ADJUSTMENT', 'REFUND_OBLIGATION'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE orders_fulfillment_projections (
    order_business_id VARCHAR(64) NOT NULL,
    required_quantity INT NOT NULL,
    shipped_quantity INT NOT NULL,
    current_receivable DECIMAL(19,2) NOT NULL,
    received_amount DECIMAL(19,2) NOT NULL,
    refunded_amount DECIMAL(19,2) NOT NULL,
    delivery_fulfilled TINYINT(1) NOT NULL,
    payment_fulfilled TINYINT(1) NOT NULL,
    blocking_reason_codes VARCHAR(200) NULL,
    updated_at DATETIME(6) NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (order_business_id),
    CONSTRAINT ck_projection_nonnegative CHECK (
        required_quantity >= 0 AND shipped_quantity >= 0 AND current_receivable >= 0
        AND received_amount >= 0 AND refunded_amount >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
