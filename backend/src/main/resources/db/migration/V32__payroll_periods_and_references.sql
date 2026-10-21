-- 工资期间、参考运行与结果；生产材料分项来源（基线 13 §5、§9）

ALTER TABLE production_material_cost_sources
    ADD COLUMN component_code VARCHAR(64) NULL AFTER scheme_snapshot_ref;

CREATE TABLE finance_payroll_periods (
    period_business_id VARCHAR(64) NOT NULL,
    period_no VARCHAR(32) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR(32) NOT NULL,
    content_version BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    PRIMARY KEY (period_business_id),
    UNIQUE KEY uk_payroll_period_no (period_no),
    CONSTRAINT ck_payroll_period_dates CHECK (start_date <= end_date),
    CONSTRAINT ck_payroll_period_status CHECK (status IN ('OPEN', 'CLOSED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE finance_payroll_results (
    result_business_id VARCHAR(64) NOT NULL,
    period_business_id VARCHAR(64) NOT NULL,
    employee_business_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    reference_run_business_id VARCHAR(64) NULL,
    reference_fingerprint CHAR(64) NULL,
    confirmed_amount DECIMAL(19,2) NULL,
    row_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (result_business_id),
    UNIQUE KEY uk_payroll_result_employee (period_business_id, employee_business_id),
    KEY ix_payroll_result_employee (employee_business_id, period_business_id),
    CONSTRAINT ck_payroll_result_status CHECK (
        status IN ('PENDING_REFERENCE', 'REFERENCE_READY', 'CONFIRMED', 'REVIEWED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE finance_payroll_reference_runs (
    run_business_id VARCHAR(64) NOT NULL,
    period_business_id VARCHAR(64) NOT NULL,
    employee_business_id VARCHAR(64) NOT NULL,
    run_type VARCHAR(16) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    source_versions_json JSON NOT NULL,
    referenced_run_business_id VARCHAR(64) NULL,
    generated_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (run_business_id),
    KEY ix_payroll_run_employee (employee_business_id, period_business_id, generated_at),
    CONSTRAINT ck_payroll_run_type CHECK (run_type IN ('BASE', 'REVIEW'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE finance_payroll_reference_lines (
    line_business_id VARCHAR(64) NOT NULL,
    run_business_id VARCHAR(64) NOT NULL,
    line_type VARCHAR(32) NOT NULL,
    source_business_id VARCHAR(64) NOT NULL,
    component_code VARCHAR(64) NULL,
    quantity INT NULL,
    unit_amount DECIMAL(19,4) NULL,
    reference_amount DECIMAL(19,2) NULL,
    occurred TINYINT(1) NOT NULL,
    estimated TINYINT(1) NOT NULL,
    material_source VARCHAR(32) NULL,
    incomplete TINYINT(1) NOT NULL,
    note VARCHAR(500) NULL,
    occurred_at DATETIME(6) NULL,
    PRIMARY KEY (line_business_id),
    KEY ix_payroll_line_run (run_business_id),
    CONSTRAINT ck_payroll_line_type CHECK (
        line_type IN ('LABOR', 'MATERIAL', 'RESPONSIBILITY', 'GLUE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
