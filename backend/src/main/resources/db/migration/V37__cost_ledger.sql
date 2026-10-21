-- 成本台账：来源守卫、成本事实、明细、分摊、失败队列与经营费用（基线 13 §6、§8.5）

CREATE TABLE finance_cost_entries (
    cost_entry_business_id VARCHAR(64) NOT NULL,
    source_module VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_business_id VARCHAR(64) NOT NULL,
    source_version BIGINT NOT NULL,
    cost_category VARCHAR(32) NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_business_id VARCHAR(64) NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    business_date DATE NOT NULL,
    operation_type VARCHAR(16) NOT NULL,
    basis_ref VARCHAR(64) NULL,
    basis_fingerprint CHAR(64) NULL,
    reason_text VARCHAR(500) NULL,
    occurred_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (cost_entry_business_id),
    UNIQUE KEY uk_cost_source (source_module, source_type, source_business_id,
                              source_version, operation_type),
    KEY ix_cost_subject (subject_type, subject_business_id, business_date),
    KEY ix_cost_date (business_date, cost_category),
    CONSTRAINT ck_cost_operation CHECK (operation_type IN ('ACCRUE', 'REVERSE', 'ADJUST'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE finance_cost_entry_lines (
    line_business_id VARCHAR(64) NOT NULL,
    cost_entry_business_id VARCHAR(64) NOT NULL,
    component_code VARCHAR(64) NOT NULL,
    process_code VARCHAR(32) NULL,
    cost_nature VARCHAR(32) NOT NULL,
    bearer_type VARCHAR(16) NOT NULL,
    quantity INT NOT NULL,
    unit_amount DECIMAL(19,4) NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    PRIMARY KEY (line_business_id),
    KEY ix_cost_line_entry (cost_entry_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE finance_cost_source_guards (
    source_module VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_business_id VARCHAR(64) NOT NULL,
    source_version BIGINT NOT NULL,
    cost_entry_business_id VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (source_module, source_type, source_business_id, source_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE finance_cost_failures (
    failure_business_id VARCHAR(64) NOT NULL,
    source_module VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_business_id VARCHAR(64) NOT NULL,
    reason_text VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (failure_business_id),
    KEY ix_cost_failure_status (status, created_at),
    CONSTRAINT ck_cost_failure_status CHECK (status IN ('PENDING', 'RESOLVED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE finance_cost_allocations (
    allocation_business_id VARCHAR(64) NOT NULL,
    cost_entry_business_id VARCHAR(64) NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_business_id VARCHAR(64) NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (allocation_business_id),
    KEY ix_cost_allocation_entry (cost_entry_business_id),
    CONSTRAINT ck_cost_allocation_amount CHECK (amount <> 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE finance_operating_expenses (
    expense_business_id VARCHAR(64) NOT NULL,
    cash_fact_business_id VARCHAR(64) NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    business_date DATE NOT NULL,
    category_code VARCHAR(32) NOT NULL,
    operation_type VARCHAR(16) NOT NULL,
    reverses_expense_business_id VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (expense_business_id),
    UNIQUE KEY uk_operating_cash (cash_fact_business_id, operation_type),
    CONSTRAINT ck_operating_expense_operation CHECK (operation_type IN ('ACCRUE', 'REVERSE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
