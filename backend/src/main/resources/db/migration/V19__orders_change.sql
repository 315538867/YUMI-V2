-- 订单变更事实与动作（基线 12 §8.2）

CREATE TABLE orders_change_facts (
    change_business_id VARCHAR(64) NOT NULL,
    order_business_id VARCHAR(64) NOT NULL,
    change_type VARCHAR(32) NOT NULL,
    reason_text VARCHAR(500) NULL,
    amount_delta DECIMAL(19,2) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    command_execution_id VARCHAR(64) NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (change_business_id),
    KEY ix_change_order (order_business_id, occurred_at),
    CONSTRAINT ck_change_type CHECK (
        change_type IN ('PRICE_OR_RECIPIENT', 'QUANTITY', 'PRODUCTION_DEFINITION', 'CANCELLATION'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE orders_change_actions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    change_business_id VARCHAR(64) NOT NULL,
    source_order_item_business_id VARCHAR(64) NOT NULL,
    source_group_business_id VARCHAR(64) NOT NULL,
    successor_order_item_business_id VARCHAR(64) NULL,
    successor_group_business_id VARCHAR(64) NULL,
    source_production_demand_business_id VARCHAR(64) NULL,
    successor_production_demand_business_id VARCHAR(64) NULL,
    action_type VARCHAR(32) NOT NULL,
    migrated_quantity INT NOT NULL DEFAULT 0,
    quantity_before INT NOT NULL,
    quantity_after INT NOT NULL,
    old_version_no INT NOT NULL,
    new_version_no INT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_change_action (change_business_id, source_group_business_id, action_type),
    CONSTRAINT ck_action_type CHECK (
        action_type IN ('VERSION_BUMP', 'QUANTITY_REDUCE', 'SUCCESSOR', 'CANCELLATION'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE orders_change_offline_transfer_links (
    id BIGINT NOT NULL AUTO_INCREMENT,
    change_business_id VARCHAR(64) NOT NULL,
    group_business_id VARCHAR(64) NOT NULL,
    production_source_business_id VARCHAR(64) NOT NULL,
    production_transfer_business_id VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_change_transfer (change_business_id, production_source_business_id),
    CONSTRAINT ck_change_transfer_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
