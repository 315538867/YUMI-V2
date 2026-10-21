-- 生产核验事实：执行、质量、缺陷、处置、终态退出、责任、劳动与材料成本来源（基线 11 §9.3）

CREATE TABLE production_defect_reasons (
    defect_reason_code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    is_other TINYINT(1) NOT NULL DEFAULT 0,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (defect_reason_code),
    CONSTRAINT ck_defect_reason_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_task_item_verifications (
    task_item_business_id VARCHAR(64) NOT NULL,
    task_business_id VARCHAR(64) NOT NULL,
    assignment_version BIGINT NOT NULL,
    planned_quantity INT NOT NULL,
    unfinished_quantity INT NOT NULL,
    completed_quantity INT NOT NULL,
    qualified_quantity INT NOT NULL,
    rework_quantity INT NOT NULL,
    scrapped_quantity INT NOT NULL,
    destroyed_quantity INT NOT NULL,
    scrap_offline_quantity INT NOT NULL,
    task_work_date DATE NOT NULL,
    verified_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    result_note VARCHAR(500) NULL,
    corrects_fact_business_id VARCHAR(64) NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (task_item_business_id),
    CONSTRAINT ck_item_verification_plan CHECK (planned_quantity = unfinished_quantity + completed_quantity),
    CONSTRAINT ck_item_verification_complete CHECK (
        completed_quantity = qualified_quantity + rework_quantity + scrapped_quantity),
    CONSTRAINT ck_item_verification_scrap CHECK (
        scrapped_quantity = destroyed_quantity + scrap_offline_quantity)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_execution_facts (
    fact_business_id VARCHAR(64) NOT NULL,
    task_business_id VARCHAR(64) NOT NULL,
    task_item_business_id VARCHAR(64) NOT NULL,
    demand_business_id VARCHAR(64) NOT NULL,
    demand_type VARCHAR(32) NOT NULL,
    production_source_business_id VARCHAR(64) NOT NULL,
    physical_root_source_business_id VARCHAR(64) NOT NULL,
    product_business_id VARCHAR(64) NOT NULL,
    product_snapshot_ref VARCHAR(64) NOT NULL,
    scheme_business_id VARCHAR(64) NOT NULL,
    scheme_snapshot_ref VARCHAR(64) NOT NULL,
    process_code VARCHAR(32) NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_business_id VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    task_work_date DATE NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    corrects_fact_business_id VARCHAR(64) NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (fact_business_id),
    KEY ix_execution_item (task_item_business_id),
    KEY ix_execution_source (production_source_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_quality_facts (
    fact_business_id VARCHAR(64) NOT NULL,
    task_item_business_id VARCHAR(64) NOT NULL,
    quality_code VARCHAR(32) NOT NULL,
    quantity INT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    corrects_fact_business_id VARCHAR(64) NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (fact_business_id),
    KEY ix_quality_item (task_item_business_id),
    CONSTRAINT ck_quality_code CHECK (quality_code IN ('QUALIFIED', 'UNQUALIFIED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_defect_facts (
    fact_business_id VARCHAR(64) NOT NULL,
    task_item_business_id VARCHAR(64) NOT NULL,
    defect_reason_code VARCHAR(32) NOT NULL,
    defect_reason_name VARCHAR(64) NOT NULL,
    defect_reason_text VARCHAR(200) NULL,
    quantity INT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    corrects_fact_business_id VARCHAR(64) NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (fact_business_id),
    KEY ix_defect_item (task_item_business_id),
    CONSTRAINT ck_defect_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_disposition_facts (
    fact_business_id VARCHAR(64) NOT NULL,
    defect_fact_business_id VARCHAR(64) NOT NULL,
    task_item_business_id VARCHAR(64) NOT NULL,
    disposition_type VARCHAR(32) NOT NULL,
    physical_destination VARCHAR(32) NOT NULL,
    quantity INT NOT NULL,
    terminal_exit_business_id VARCHAR(64) NULL,
    repair_source_business_id VARCHAR(64) NULL,
    occurred_at DATETIME(6) NOT NULL,
    corrects_fact_business_id VARCHAR(64) NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (fact_business_id),
    KEY ix_disposition_defect (defect_fact_business_id),
    CONSTRAINT ck_disposition_type CHECK (disposition_type IN ('REWORK_REQUIRED', 'SCRAPPED')),
    CONSTRAINT ck_disposition_destination CHECK (
        physical_destination IN ('IN_SYSTEM', 'DESTROYED', 'TRANSFERRED_OFFLINE_INVENTORY')),
    CONSTRAINT ck_disposition_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_terminal_exits (
    terminal_exit_business_id VARCHAR(64) NOT NULL,
    production_source_business_id VARCHAR(64) NOT NULL,
    physical_root_source_business_id VARCHAR(64) NOT NULL,
    demand_business_id VARCHAR(64) NOT NULL,
    -- 移交产生的退出没有任务明细；核验产生的退出必填
    task_item_business_id VARCHAR(64) NULL,
    exit_reason VARCHAR(32) NOT NULL,
    physical_destination VARCHAR(32) NOT NULL,
    quantity INT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    corrects_fact_business_id VARCHAR(64) NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (terminal_exit_business_id),
    CONSTRAINT ck_terminal_exit_reason CHECK (
        exit_reason IN ('QUALITY_SCRAP', 'UPSTREAM_RELEASE')),
    CONSTRAINT ck_terminal_exit_destination CHECK (
        physical_destination IN ('DESTROYED', 'TRANSFERRED_OFFLINE_INVENTORY')),
    CONSTRAINT ck_terminal_exit_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_responsibility_facts (
    responsibility_chain_business_id VARCHAR(64) NOT NULL,
    disposition_fact_business_id VARCHAR(64) NOT NULL,
    terminal_exit_business_id VARCHAR(64) NOT NULL,
    responsibility_type VARCHAR(32) NOT NULL,
    responsible_subject_business_id VARCHAR(64) NULL,
    waived_reason VARCHAR(200) NULL,
    quantity INT NOT NULL,
    business_date DATE NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    corrects_fact_business_id VARCHAR(64) NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    row_version BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (responsibility_chain_business_id),
    CONSTRAINT ck_responsibility_type CHECK (
        responsibility_type IN ('EMPLOYEE', 'TEAM', 'STUDIO', 'CUSTOMER', 'WAIVED')),
    CONSTRAINT ck_responsibility_subject CHECK (
        (responsibility_type = 'WAIVED' AND waived_reason IS NOT NULL)
        OR (responsibility_type <> 'WAIVED' AND responsible_subject_business_id IS NOT NULL)),
    CONSTRAINT ck_responsibility_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_other_task_verification_facts (
    fact_business_id VARCHAR(64) NOT NULL,
    task_business_id VARCHAR(64) NOT NULL,
    assignment_version BIGINT NOT NULL,
    assessment_business_id VARCHAR(64) NULL,
    planned_quantity INT NULL,
    completed_quantity INT NULL,
    unfinished_quantity INT NULL,
    result_note VARCHAR(500) NULL,
    verified_at DATETIME(6) NOT NULL,
    task_work_date DATE NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    corrects_fact_business_id VARCHAR(64) NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (fact_business_id),
    UNIQUE KEY uk_other_task_verification (task_business_id),
    CONSTRAINT ck_other_task_quantity CHECK (
        planned_quantity IS NULL
        OR planned_quantity = completed_quantity + unfinished_quantity)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_labor_sources (
    labor_source_business_id VARCHAR(64) NOT NULL,
    task_business_id VARCHAR(64) NOT NULL,
    task_item_business_id VARCHAR(64) NULL,
    demand_business_id VARCHAR(64) NULL,
    production_source_business_id VARCHAR(64) NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_business_id VARCHAR(64) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    process_code VARCHAR(32) NULL,
    quantity INT NULL,
    unit_labor_amount DECIMAL(19,4) NULL,
    business_date DATE NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    effective_status VARCHAR(16) NOT NULL,
    reverses_labor_source_business_id VARCHAR(64) NULL,
    PRIMARY KEY (labor_source_business_id),
    KEY ix_labor_task (task_business_id),
    CONSTRAINT ck_labor_status CHECK (effective_status IN ('APPLY', 'REVERSE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_material_cost_sources (
    material_source_business_id VARCHAR(64) NOT NULL,
    task_business_id VARCHAR(64) NOT NULL,
    task_item_business_id VARCHAR(64) NOT NULL,
    demand_business_id VARCHAR(64) NOT NULL,
    production_source_business_id VARCHAR(64) NOT NULL,
    physical_root_source_business_id VARCHAR(64) NOT NULL,
    product_business_id VARCHAR(64) NOT NULL,
    product_snapshot_ref VARCHAR(64) NOT NULL,
    scheme_business_id VARCHAR(64) NOT NULL,
    scheme_snapshot_ref VARCHAR(64) NOT NULL,
    process_code VARCHAR(32) NOT NULL,
    quantity INT NOT NULL,
    business_date DATE NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    effective_status VARCHAR(16) NOT NULL,
    reverses_material_source_business_id VARCHAR(64) NULL,
    PRIMARY KEY (material_source_business_id),
    KEY ix_material_task (task_business_id),
    CONSTRAINT ck_material_status CHECK (effective_status IN ('APPLY', 'REVERSE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
