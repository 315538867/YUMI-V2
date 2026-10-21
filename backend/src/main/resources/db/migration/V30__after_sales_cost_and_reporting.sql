-- 售后费用来源与案例更新时间（基线 12 §13.3、14）

ALTER TABLE after_sales_cases
    ADD COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) AFTER opened_at;

CREATE TABLE after_sales_cost_source_items (
    feed_sequence BIGINT NOT NULL AUTO_INCREMENT,
    source_business_id VARCHAR(64) NOT NULL,
    source_version INT NOT NULL,
    case_business_id VARCHAR(64) NOT NULL,
    order_business_id VARCHAR(64) NOT NULL,
    cost_category VARCHAR(32) NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    business_date DATE NOT NULL,
    operation_type VARCHAR(16) NOT NULL,
    basis_ref VARCHAR(64) NULL,
    reason_text VARCHAR(500) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (feed_sequence),
    UNIQUE KEY uk_after_sales_cost_source (source_business_id, source_version, operation_type),
    KEY ix_after_sales_cost_case (case_business_id, occurred_at),
    CONSTRAINT ck_after_sales_cost_amount CHECK (amount >= 0),
    CONSTRAINT ck_after_sales_cost_operation CHECK (operation_type IN ('ACCRUE', 'REVERSE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
