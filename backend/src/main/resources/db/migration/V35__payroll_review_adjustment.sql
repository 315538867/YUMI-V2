-- 已核定工资复核与人工差额调整（基线 13 §5.1.4）

ALTER TABLE finance_payroll_results
    ADD COLUMN payable_amount DECIMAL(19,2) NOT NULL DEFAULT 0 AFTER confirmed_amount;

CREATE TABLE finance_payroll_adjustments (
    adjustment_business_id VARCHAR(64) NOT NULL,
    result_business_id VARCHAR(64) NOT NULL,
    amount_delta DECIMAL(19,2) NOT NULL,
    reference_run_business_id VARCHAR(64) NULL,
    reason_text VARCHAR(500) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (adjustment_business_id),
    KEY ix_payroll_adjustment_result (result_business_id, occurred_at),
    CONSTRAINT ck_payroll_adjustment_nonzero CHECK (amount_delta <> 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE finance_direct_labor_cost_adjustments (
    adjustment_business_id VARCHAR(64) NOT NULL,
    result_business_id VARCHAR(64) NOT NULL,
    amount_delta DECIMAL(19,4) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (adjustment_business_id),
    CONSTRAINT ck_labor_cost_adjustment_nonzero CHECK (amount_delta <> 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
