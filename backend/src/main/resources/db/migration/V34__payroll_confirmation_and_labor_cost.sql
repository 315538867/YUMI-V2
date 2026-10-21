-- 逐人工资核定、人工处理明细与直接人工成本（基线 13 §8.1）

CREATE TABLE finance_payroll_confirmations (
    confirmation_business_id VARCHAR(64) NOT NULL,
    result_business_id VARCHAR(64) NOT NULL,
    confirmed_amount DECIMAL(19,2) NOT NULL,
    reference_run_business_id VARCHAR(64) NOT NULL,
    reference_fingerprint CHAR(64) NOT NULL,
    note VARCHAR(500) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (confirmation_business_id),
    KEY ix_payroll_confirmation_result (result_business_id, occurred_at),
    CONSTRAINT ck_payroll_confirmed_amount CHECK (confirmed_amount >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE finance_payroll_processing_items (
    item_business_id VARCHAR(64) NOT NULL,
    result_business_id VARCHAR(64) NOT NULL,
    confirmation_business_id VARCHAR(64) NOT NULL,
    reference_line_business_id VARCHAR(64) NOT NULL,
    manual_amount DECIMAL(19,2) NULL,
    note VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (item_business_id),
    KEY ix_payroll_processing_result (result_business_id),
    CONSTRAINT ck_processing_amount CHECK (manual_amount IS NULL OR manual_amount >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE finance_direct_labor_costs (
    cost_source_business_id VARCHAR(64) NOT NULL,
    result_business_id VARCHAR(64) NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    business_date DATE NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (cost_source_business_id),
    UNIQUE KEY uk_direct_labor_result (result_business_id),
    CONSTRAINT ck_direct_labor_amount CHECK (amount >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
