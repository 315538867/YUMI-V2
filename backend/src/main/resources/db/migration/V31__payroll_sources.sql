-- 工资来源摄取游标与条目（基线 13 §9）

CREATE TABLE finance_payroll_source_cursors (
    feed_code VARCHAR(32) NOT NULL,
    cursor_time DATETIME(6) NULL,
    cursor_source_id VARCHAR(64) NULL,
    cursor_sequence BIGINT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (feed_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE finance_payroll_labor_entries (
    entry_business_id VARCHAR(64) NOT NULL,
    source_labor_source_business_id VARCHAR(64) NOT NULL,
    task_business_id VARCHAR(64) NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_business_id VARCHAR(64) NOT NULL,
    participants_json JSON NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    process_code VARCHAR(32) NULL,
    quantity INT NULL,
    unit_labor_amount DECIMAL(19,4) NULL,
    business_date DATE NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    effective_status VARCHAR(16) NOT NULL,
    reverses_source_id VARCHAR(64) NULL,
    ingested_at DATETIME(6) NOT NULL,
    PRIMARY KEY (entry_business_id),
    UNIQUE KEY uk_payroll_labor_source (source_labor_source_business_id),
    KEY ix_payroll_labor_date (business_date, occurred_at),
    CONSTRAINT ck_payroll_labor_status CHECK (effective_status IN ('APPLY', 'REVERSE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE finance_payroll_responsibility_entries (
    entry_business_id VARCHAR(64) NOT NULL,
    source_revision_business_id VARCHAR(64) NOT NULL,
    feed_sequence BIGINT NOT NULL,
    responsibility_chain_business_id VARCHAR(64) NOT NULL,
    disposition_fact_business_id VARCHAR(64) NOT NULL,
    effect_type VARCHAR(16) NOT NULL,
    responsibility_type VARCHAR(32) NOT NULL,
    responsible_subject_business_id VARCHAR(64) NULL,
    quantity INT NOT NULL,
    business_date DATE NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    ingested_at DATETIME(6) NOT NULL,
    PRIMARY KEY (entry_business_id),
    UNIQUE KEY uk_payroll_responsibility_source (source_revision_business_id),
    UNIQUE KEY uk_payroll_responsibility_sequence (feed_sequence),
    CONSTRAINT ck_payroll_responsibility_effect CHECK (effect_type IN ('APPLY', 'REVERSE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE finance_payroll_month_guards (
    employee_business_id VARCHAR(64) NOT NULL,
    month_start DATE NOT NULL,
    source_version BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (employee_business_id, month_start)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
