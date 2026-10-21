-- 售后案例、问题事实、义务与完成事实（基线 12 §13.3）

CREATE TABLE after_sales_cases (
    case_business_id VARCHAR(64) NOT NULL,
    case_no VARCHAR(32) NOT NULL,
    order_business_id VARCHAR(64) NOT NULL,
    customer_business_id VARCHAR(64) NOT NULL,
    issue_summary VARCHAR(500) NOT NULL,
    route_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    opened_at DATETIME(6) NOT NULL,
    content_version BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (case_business_id),
    UNIQUE KEY uk_after_sales_case_no (case_no),
    KEY ix_after_sales_order (order_business_id, opened_at),
    CONSTRAINT ck_after_sales_status CHECK (status IN ('OPEN', 'PROCESSING', 'COMPLETED')),
    CONSTRAINT ck_after_sales_route CHECK (route_type IN (
        'RETURN_AND_REWORK', 'DIRECT_REPLACEMENT', 'REFUND_ONLY', 'COMPENSATION_ONLY', 'MIXED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE after_sales_issue_facts (
    fact_business_id VARCHAR(64) NOT NULL,
    case_business_id VARCHAR(64) NOT NULL,
    issue_type VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NULL,
    description VARCHAR(500) NOT NULL,
    evidence_count INT NOT NULL DEFAULT 0,
    occurred_at DATETIME(6) NOT NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (fact_business_id),
    KEY ix_issue_case (case_business_id, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE after_sales_obligations (
    obligation_business_id VARCHAR(64) NOT NULL,
    case_business_id VARCHAR(64) NOT NULL,
    obligation_type VARCHAR(32) NOT NULL,
    bearer_type VARCHAR(16) NOT NULL,
    predecessor_obligation_business_id VARCHAR(64) NULL,
    created_by_change_business_id VARCHAR(64) NULL,
    target_quantity INT NULL,
    target_amount DECIMAL(19,2) NULL,
    due_date DATE NULL,
    note VARCHAR(500) NULL,
    status VARCHAR(32) NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (obligation_business_id),
    KEY ix_obligation_case (case_business_id),
    CONSTRAINT ck_obligation_type CHECK (obligation_type IN (
        'CUSTOMER_COOPERATION', 'RETURN_AND_REPAIR', 'REPLACEMENT', 'REFUND', 'COMPENSATION')),
    CONSTRAINT ck_obligation_bearer CHECK (bearer_type IN ('CUSTOMER', 'WORKSHOP')),
    CONSTRAINT ck_obligation_status CHECK (status IN ('PENDING', 'IN_PROGRESS', 'FULFILLED', 'VOIDED')),
    CONSTRAINT ck_obligation_target CHECK (
        (target_quantity IS NOT NULL AND target_quantity > 0 AND target_amount IS NULL)
        OR (target_amount IS NOT NULL AND target_amount >= 0 AND target_quantity IS NULL)
        OR (target_quantity IS NULL AND target_amount IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE after_sales_completion_facts (
    fact_business_id VARCHAR(64) NOT NULL,
    case_business_id VARCHAR(64) NOT NULL,
    fact_type VARCHAR(32) NOT NULL,
    obligation_fingerprint CHAR(64) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (fact_business_id),
    UNIQUE KEY uk_after_sales_completion (case_business_id, fact_type),
    CONSTRAINT ck_after_sales_completion_type CHECK (fact_type IN ('COMPLETED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
