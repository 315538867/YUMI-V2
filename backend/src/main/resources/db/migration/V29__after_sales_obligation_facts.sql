-- 售后义务变更事实（基线 12 §13.3）

CREATE TABLE after_sales_obligation_facts (
    fact_business_id VARCHAR(64) NOT NULL,
    obligation_business_id VARCHAR(64) NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    quantity INT NULL,
    amount DECIMAL(19,2) NULL,
    quantity_resolution_refs_json JSON NULL,
    note VARCHAR(500) NULL,
    corrects_fact_business_id VARCHAR(64) NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (fact_business_id),
    KEY ix_obligation_fact (obligation_business_id, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
