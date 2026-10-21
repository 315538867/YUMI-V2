-- 已有订单接管与生产来源期初事实（基线 12 §8.4、11 §5.5、§7.9）

CREATE TABLE orders_legacy_intakes (
    intake_business_id VARCHAR(64) NOT NULL,
    order_business_id VARCHAR(64) NOT NULL,
    original_order_reference VARCHAR(100) NULL,
    note VARCHAR(500) NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    command_execution_id VARCHAR(64) NULL,
    PRIMARY KEY (intake_business_id),
    UNIQUE KEY uk_legacy_intake_order (order_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE orders_legacy_intake_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    intake_business_id VARCHAR(64) NOT NULL,
    order_item_business_id VARCHAR(64) NOT NULL,
    intake_quantity INT NOT NULL,
    note VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_legacy_intake_item (intake_business_id, order_item_business_id),
    CONSTRAINT ck_legacy_intake_item_quantity CHECK (intake_quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE orders_legacy_state_lines (
    id BIGINT NOT NULL AUTO_INCREMENT,
    intake_business_id VARCHAR(64) NOT NULL,
    order_item_business_id VARCHAR(64) NOT NULL,
    stage_code VARCHAR(32) NOT NULL,
    quality_code VARCHAR(32) NOT NULL,
    disposition_code VARCHAR(32) NOT NULL,
    physical_destination VARCHAR(32) NOT NULL,
    rework_process_code VARCHAR(32) NULL,
    quantity INT NOT NULL,
    reason_text VARCHAR(500) NULL,
    PRIMARY KEY (id),
    KEY ix_legacy_state_item (order_item_business_id),
    CONSTRAINT ck_legacy_state_quantity CHECK (quantity > 0),
    CONSTRAINT ck_legacy_state_stage CHECK (
        stage_code IN ('NOT_STARTED', 'MAKING', 'BAGGING', 'SEAM_CUTTING', 'REWORK_REQUIRED', 'SCRAPPED')),
    CONSTRAINT ck_legacy_state_destination CHECK (
        physical_destination IN ('IN_SYSTEM', 'DESTROYED', 'TRANSFERRED_OFFLINE_INVENTORY'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_opening_facts (
    fact_business_id VARCHAR(64) NOT NULL,
    opening_batch_business_id VARCHAR(64) NOT NULL,
    demand_business_id VARCHAR(64) NOT NULL,
    production_source_business_id VARCHAR(64) NOT NULL,
    stage_code VARCHAR(32) NOT NULL,
    quality_code VARCHAR(32) NOT NULL,
    disposition_code VARCHAR(32) NOT NULL,
    physical_destination VARCHAR(32) NOT NULL,
    rework_process_code VARCHAR(32) NULL,
    quantity INT NOT NULL,
    reason_code VARCHAR(32) NULL,
    reason_text VARCHAR(500) NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (fact_business_id),
    KEY ix_opening_fact_source (production_source_business_id),
    KEY ix_opening_fact_batch (opening_batch_business_id),
    CONSTRAINT ck_opening_fact_quantity CHECK (quantity > 0),
    CONSTRAINT ck_opening_fact_stage CHECK (
        stage_code IN ('NOT_STARTED', 'MAKING', 'BAGGING', 'SEAM_CUTTING', 'REWORK_REQUIRED', 'SCRAPPED')),
    CONSTRAINT ck_opening_fact_destination CHECK (
        physical_destination IN ('IN_SYSTEM', 'DESTROYED', 'TRANSFERRED_OFFLINE_INVENTORY'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
