-- 真实现金、来源投影、工资退回与售后支付关联（基线 13 §7、§8.2—§8.4）

CREATE TABLE finance_cash_facts (
    cash_fact_business_id VARCHAR(64) NOT NULL,
    cash_no VARCHAR(32) NOT NULL,
    direction VARCHAR(8) NOT NULL,
    cash_type VARCHAR(32) NOT NULL,
    business_date DATE NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    currency_code CHAR(3) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    reverses_fact_business_id VARCHAR(64) NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (cash_fact_business_id),
    UNIQUE KEY uk_cash_no (cash_no),
    UNIQUE KEY uk_cash_reversal (reverses_fact_business_id),
    KEY ix_cash_date (business_date, direction),
    CONSTRAINT ck_cash_direction CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT ck_cash_amount CHECK (amount > 0),
    CONSTRAINT ck_cash_type CHECK (cash_type IN (
        'ORDER_RECEIPT', 'ORDER_REFUND', 'AFTER_SALES_REFUND', 'AFTER_SALES_COMPENSATION',
        'PAYROLL_PAYMENT', 'PAYROLL_RETURN', 'DAILY_INCOME', 'DAILY_EXPENSE', 'REVERSAL'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE finance_cash_allocations (
    allocation_business_id VARCHAR(64) NOT NULL,
    cash_fact_business_id VARCHAR(64) NOT NULL,
    source_module VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_business_id VARCHAR(64) NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    PRIMARY KEY (allocation_business_id),
    UNIQUE KEY uk_cash_allocation_source (cash_fact_business_id, source_type, source_business_id),
    KEY ix_cash_allocation_source (source_module, source_type, source_business_id),
    CONSTRAINT ck_cash_allocation_amount CHECK (amount > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE finance_source_guards (
    source_module VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_business_id VARCHAR(64) NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (source_module, source_type, source_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE finance_source_projections (
    source_module VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_business_id VARCHAR(64) NOT NULL,
    received_amount DECIMAL(19,2) NOT NULL DEFAULT 0,
    paid_amount DECIMAL(19,2) NOT NULL DEFAULT 0,
    refunded_amount DECIMAL(19,2) NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (source_module, source_type, source_business_id),
    CONSTRAINT ck_source_projection_nonnegative CHECK (
        received_amount >= 0 AND paid_amount >= 0 AND refunded_amount >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE finance_cash_voucher_links (
    link_business_id VARCHAR(64) NOT NULL,
    cash_fact_business_id VARCHAR(64) NOT NULL,
    file_business_id VARCHAR(64) NOT NULL,
    purpose_code VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (link_business_id),
    UNIQUE KEY uk_cash_voucher (cash_fact_business_id, purpose_code, file_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE finance_payroll_return_details (
    return_cash_fact_business_id VARCHAR(64) NOT NULL,
    original_payment_cash_fact_business_id VARCHAR(64) NOT NULL,
    original_payment_allocation_business_id VARCHAR(64) NOT NULL,
    result_business_id VARCHAR(64) NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    reason_text VARCHAR(500) NOT NULL,
    PRIMARY KEY (return_cash_fact_business_id),
    KEY ix_payroll_return_allocation (original_payment_allocation_business_id),
    CONSTRAINT ck_payroll_return_amount CHECK (amount > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE after_sales_payment_link_facts (
    fact_business_id VARCHAR(64) NOT NULL,
    obligation_business_id VARCHAR(64) NOT NULL,
    finance_cash_fact_business_id VARCHAR(64) NOT NULL,
    linked_amount DECIMAL(19,2) NOT NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    corrects_fact_business_id VARCHAR(64) NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (fact_business_id),
    UNIQUE KEY uk_after_sales_payment_cash (obligation_business_id, finance_cash_fact_business_id),
    CONSTRAINT ck_after_sales_payment_amount CHECK (linked_amount > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
