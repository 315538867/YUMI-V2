-- 工资支付参与关联与已付投影（基线 13 §5.1.5、§8.2）

CREATE TABLE finance_payroll_payment_links (
    link_business_id VARCHAR(64) NOT NULL,
    result_business_id VARCHAR(64) NOT NULL,
    finance_cash_fact_business_id VARCHAR(64) NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    reverses_link_business_id VARCHAR(64) NULL,
    reason_text VARCHAR(500) NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (link_business_id),
    UNIQUE KEY uk_payroll_payment_cash (result_business_id, finance_cash_fact_business_id),
    UNIQUE KEY uk_payroll_payment_reversal (reverses_link_business_id),
    CONSTRAINT ck_payroll_payment_amount CHECK (amount > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE finance_payroll_payment_projections (
    result_business_id VARCHAR(64) NOT NULL,
    paid_amount DECIMAL(19,2) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (result_business_id),
    CONSTRAINT ck_payroll_paid_nonnegative CHECK (paid_amount >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
