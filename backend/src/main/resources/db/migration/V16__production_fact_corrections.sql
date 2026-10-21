-- 生产事实更正：每个更正命令一个批次，批次内 REVERSE 旧项 + APPLY 新项（基线 11 §7.8）

CREATE TABLE production_fact_corrections (
    correction_command_business_id VARCHAR(64) NOT NULL,
    task_item_business_id VARCHAR(64) NOT NULL,
    task_business_id VARCHAR(64) NOT NULL,
    demand_business_id VARCHAR(64) NOT NULL,
    production_source_business_id VARCHAR(64) NOT NULL,
    previous_verification_event_business_id VARCHAR(64) NULL,
    correction_reason VARCHAR(500) NOT NULL,
    declared_item_count INT NOT NULL,
    command_fingerprint CHAR(64) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    feed_sequence BIGINT NOT NULL AUTO_INCREMENT,
    PRIMARY KEY (correction_command_business_id),
    UNIQUE KEY uk_correction_feed_sequence (feed_sequence),
    KEY ix_correction_item (task_item_business_id, occurred_at),
    CONSTRAINT ck_correction_item_count CHECK (declared_item_count > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_fact_correction_items (
    correction_item_business_id VARCHAR(64) NOT NULL,
    correction_command_business_id VARCHAR(64) NOT NULL,
    item_index INT NOT NULL,
    item_kind VARCHAR(32) NOT NULL,
    effect_type VARCHAR(16) NOT NULL,
    referenced_fact_business_id VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    amount DECIMAL(19,4) NULL,
    business_date DATE NOT NULL,
    PRIMARY KEY (correction_item_business_id),
    UNIQUE KEY uk_correction_item_index (correction_command_business_id, item_index),
    CONSTRAINT ck_correction_item_kind CHECK (
        item_kind IN ('LABOR', 'MATERIAL', 'RESPONSIBILITY')),
    CONSTRAINT ck_correction_effect CHECK (effect_type IN ('REVERSE', 'APPLY')),
    CONSTRAINT ck_correction_item_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_fact_correction_applications (
    correction_command_business_id VARCHAR(64) NOT NULL,
    applied_at DATETIME(6) NOT NULL,
    PRIMARY KEY (correction_command_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
