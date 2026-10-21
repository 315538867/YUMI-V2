-- 工资实际部分退回关联（基线 13 §7.1、§8.3）

CREATE TABLE finance_payroll_payment_returns (
    return_link_business_id VARCHAR(64) NOT NULL,
    result_business_id VARCHAR(64) NOT NULL,
    original_finance_cash_fact_id VARCHAR(64) NOT NULL,
    return_finance_cash_fact_id VARCHAR(64) NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (return_link_business_id),
    UNIQUE KEY uk_payroll_return_cash (return_finance_cash_fact_id),
    KEY ix_payroll_return_original (original_finance_cash_fact_id),
    CONSTRAINT ck_payroll_return_positive CHECK (amount > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
