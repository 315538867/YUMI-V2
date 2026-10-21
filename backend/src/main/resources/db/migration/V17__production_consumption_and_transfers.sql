-- 生产来源消费与线下库存单向移交（基线 11 §5.6、§5.7、§9.3）

CREATE TABLE production_consumption_facts (
    consumption_fact_business_id VARCHAR(64) NOT NULL,
    effect_type VARCHAR(16) NOT NULL,
    reverses_fact_business_id VARCHAR(64) NULL,
    source_module VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_business_id VARCHAR(64) NOT NULL,
    production_source_business_id VARCHAR(64) NOT NULL,
    physical_root_source_business_id VARCHAR(64) NOT NULL,
    demand_business_id VARCHAR(64) NOT NULL,
    product_business_id VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (consumption_fact_business_id),
    KEY ix_consumption_delivery (source_module, source_type, source_business_id, is_valid),
    KEY ix_consumption_production_source (production_source_business_id, is_valid),
    CONSTRAINT ck_consumption_effect CHECK (effect_type IN ('CONSUME', 'REVERSE')),
    CONSTRAINT ck_consumption_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_offline_inventory_transfers (
    transfer_fact_business_id VARCHAR(64) NOT NULL,
    terminal_exit_business_id VARCHAR(64) NOT NULL,
    production_source_business_id VARCHAR(64) NOT NULL,
    physical_root_source_business_id VARCHAR(64) NOT NULL,
    demand_business_id VARCHAR(64) NOT NULL,
    product_business_id VARCHAR(64) NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    upstream_change_business_id VARCHAR(64) NULL,
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
    UNIQUE KEY uk_transfer_terminal_exit (terminal_exit_business_id),
    KEY ix_transfer_source (production_source_business_id, is_valid),
    CONSTRAINT ck_transfer_reason CHECK (reason_code IN ('ORDER_REDUCTION', 'ORDER_CANCELLED',
        'AFTER_SALES_OBLIGATION_RELEASED', 'QUALITY_SCRAP')),
    CONSTRAINT ck_transfer_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
