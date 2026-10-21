-- 胶水自然月设置与减免参考（基线 13 §5.1.3）

ALTER TABLE finance_payroll_responsibility_entries
    ADD COLUMN glue_grams_per_piece DECIMAL(19,4) NULL AFTER quantity,
    ADD COLUMN glue_unit_price_per_gram DECIMAL(19,4) NULL AFTER glue_grams_per_piece,
    ADD COLUMN glue_eligible TINYINT(1) NOT NULL DEFAULT 0 AFTER glue_unit_price_per_gram;

CREATE TABLE finance_payroll_glue_policies (
    policy_business_id VARCHAR(64) NOT NULL,
    effective_month DATE NOT NULL,
    version BIGINT NOT NULL,
    exempt_weight_grams DECIMAL(19,4) NOT NULL,
    reason_text VARCHAR(500) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (policy_business_id),
    UNIQUE KEY uk_glue_policy_month_version (effective_month, version),
    CONSTRAINT ck_glue_policy_weight CHECK (exempt_weight_grams >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE finance_payroll_glue_policy_guard (
    guard_code VARCHAR(32) NOT NULL,
    guard_version BIGINT NOT NULL,
    PRIMARY KEY (guard_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE finance_payroll_glue_month_references (
    reference_business_id VARCHAR(64) NOT NULL,
    employee_business_id VARCHAR(64) NOT NULL,
    month_start DATE NOT NULL,
    policy_business_id VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    incomplete_reason VARCHAR(64) NULL,
    allocated_weight DECIMAL(19,4) NOT NULL,
    exempt_weight DECIMAL(19,4) NOT NULL,
    reference_fingerprint CHAR(64) NOT NULL,
    source_version BIGINT NOT NULL,
    computed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (reference_business_id),
    UNIQUE KEY uk_glue_month_employee (employee_business_id, month_start),
    CONSTRAINT ck_glue_month_status CHECK (status IN ('READY', 'INCOMPLETE')),
    CONSTRAINT ck_glue_month_weight CHECK (
        allocated_weight >= 0 AND exempt_weight >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE finance_payroll_glue_month_lines (
    line_business_id VARCHAR(64) NOT NULL,
    reference_business_id VARCHAR(64) NOT NULL,
    responsibility_chain_business_id VARCHAR(64) NOT NULL,
    cost_line_key VARCHAR(128) NOT NULL,
    business_date DATE NOT NULL,
    allocated_weight DECIMAL(19,4) NOT NULL,
    exempt_weight DECIMAL(19,4) NOT NULL,
    chargeable_weight DECIMAL(19,4) NOT NULL,
    glue_unit_price DECIMAL(19,4) NOT NULL,
    pre_amount DECIMAL(19,4) NOT NULL,
    post_amount DECIMAL(19,4) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (line_business_id),
    KEY ix_glue_month_line (reference_business_id),
    CONSTRAINT ck_glue_line_weight CHECK (
        allocated_weight = exempt_weight + chargeable_weight)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
