-- 作业任务、任务明细、安排事实、负载与产能守卫（基线 11 §9.1、§9.2）

CREATE TABLE production_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_business_id VARCHAR(64) NOT NULL,
    task_no VARCHAR(32) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    work_date DATE NOT NULL,
    process_code VARCHAR(32) NULL,
    planned_work_quantity INT NOT NULL,
    entered_standard_minutes INT NULL,
    task_standard_seconds BIGINT NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_business_id VARCHAR(64) NOT NULL,
    subject_snapshot_ref VARCHAR(64) NOT NULL,
    assessment_business_id VARCHAR(64) NULL,
    work_time_standard_snapshot_ref VARCHAR(64) NULL,
    efficiency_snapshot_ref VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    active_assignment_version BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_business_id (task_business_id),
    UNIQUE KEY uk_task_no (task_no),
    KEY ix_task_work_date (work_date, subject_type, subject_business_id, status),
    CONSTRAINT ck_task_type CHECK (
        task_type IN ('NORMAL_PRODUCTION', 'EXTRA_PRODUCTION', 'REPAIR', 'OTHER_TASK')),
    CONSTRAINT ck_task_subject CHECK (subject_type IN ('EMPLOYEE', 'TEAM')),
    CONSTRAINT ck_task_quantity CHECK (planned_work_quantity >= 0),
    CONSTRAINT ck_task_seconds CHECK (task_standard_seconds >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_other_task_details (
    task_business_id VARCHAR(64) NOT NULL,
    work_content VARCHAR(200) NOT NULL,
    related_product_business_id VARCHAR(64) NULL,
    product_snapshot_ref VARCHAR(64) NULL,
    planned_quantity INT NULL,
    frozen_unit_labor_amount DECIMAL(19,4) NULL,
    result_note VARCHAR(500) NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (task_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_task_items (
    task_item_business_id VARCHAR(64) NOT NULL,
    task_business_id VARCHAR(64) NOT NULL,
    item_order INT NOT NULL,
    demand_business_id VARCHAR(64) NOT NULL,
    demand_type VARCHAR(32) NOT NULL,
    production_source_business_id VARCHAR(64) NOT NULL,
    physical_root_source_business_id VARCHAR(64) NOT NULL,
    repair_source_business_id VARCHAR(64) NULL,
    order_business_id VARCHAR(64) NULL,
    order_item_business_id VARCHAR(64) NULL,
    product_business_id VARCHAR(64) NOT NULL,
    product_snapshot_ref VARCHAR(64) NOT NULL,
    scheme_business_id VARCHAR(64) NOT NULL,
    scheme_snapshot_ref VARCHAR(64) NOT NULL,
    target_process VARCHAR(32) NOT NULL,
    planned_quantity INT NOT NULL,
    item_standard_minutes INT NOT NULL,
    verification_status VARCHAR(32) NOT NULL,
    completed_quantity INT NULL,
    unfinished_quantity INT NULL,
    qualified_quantity INT NULL,
    rework_quantity INT NULL,
    scrapped_quantity INT NULL,
    destroyed_quantity INT NULL,
    scrap_offline_quantity INT NULL,
    cancelled_quantity INT NULL,
    assignment_version BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (task_item_business_id),
    UNIQUE KEY uk_task_item_order (task_business_id, item_order),
    KEY ix_task_item_source (production_source_business_id),
    KEY ix_task_item_repair_source (repair_source_business_id),
    CONSTRAINT ck_task_item_quantity CHECK (planned_quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_task_assignment_facts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    fact_business_id VARCHAR(64) NOT NULL,
    task_business_id VARCHAR(64) NOT NULL,
    assignment_version BIGINT NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    old_subject_ref VARCHAR(64) NULL,
    new_subject_ref VARCHAR(64) NULL,
    old_work_date DATE NULL,
    new_work_date DATE NULL,
    old_process_code VARCHAR(32) NULL,
    new_process_code VARCHAR(32) NULL,
    old_quantity INT NULL,
    new_quantity INT NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    command_execution_id VARCHAR(64) NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_assignment_version (task_business_id, assignment_version),
    UNIQUE KEY uk_task_assignment_fact (fact_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_task_item_assignment_facts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    fact_business_id VARCHAR(64) NOT NULL,
    task_business_id VARCHAR(64) NOT NULL,
    task_item_business_id VARCHAR(64) NOT NULL,
    assignment_version BIGINT NOT NULL,
    item_order INT NOT NULL,
    demand_business_id VARCHAR(64) NOT NULL,
    production_source_business_id VARCHAR(64) NOT NULL,
    order_business_id VARCHAR(64) NULL,
    order_item_business_id VARCHAR(64) NULL,
    product_business_id VARCHAR(64) NOT NULL,
    product_snapshot_ref VARCHAR(64) NOT NULL,
    scheme_business_id VARCHAR(64) NOT NULL,
    scheme_snapshot_ref VARCHAR(64) NOT NULL,
    target_process VARCHAR(32) NOT NULL,
    planned_quantity INT NOT NULL,
    item_standard_minutes INT NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    command_execution_id VARCHAR(64) NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_item_assignment (
        task_business_id, assignment_version, task_item_business_id),
    UNIQUE KEY uk_task_item_assignment_fact (fact_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_workload_assessments (
    assessment_business_id VARCHAR(64) NOT NULL,
    task_business_id VARCHAR(64) NOT NULL,
    assignment_version BIGINT NOT NULL,
    task_standard_seconds BIGINT NOT NULL,
    available_effective_seconds BIGINT NOT NULL,
    assessment_result VARCHAR(32) NOT NULL,
    subject_snapshot_ref VARCHAR(64) NOT NULL,
    work_time_standard_snapshot_ref VARCHAR(64) NOT NULL,
    efficiency_snapshot_ref VARCHAR(64) NULL,
    formula_rule_version VARCHAR(32) NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    override_reason VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (assessment_business_id),
    UNIQUE KEY uk_assessment_task_version (task_business_id, assignment_version),
    CONSTRAINT ck_assessment_result CHECK (
        assessment_result IN ('UNDER_STANDARD', 'WITHIN_STANDARD', 'OVER_STANDARD', 'UNAVAILABLE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_workload_assessment_items (
    assessment_business_id VARCHAR(64) NOT NULL,
    task_item_business_id VARCHAR(64) NOT NULL,
    product_snapshot_ref VARCHAR(64) NOT NULL,
    scheme_snapshot_ref VARCHAR(64) NOT NULL,
    process_code VARCHAR(32) NOT NULL,
    quantity INT NOT NULL,
    item_standard_minutes INT NOT NULL,
    item_base_seconds BIGINT NOT NULL,
    item_input_fingerprint CHAR(64) NOT NULL,
    PRIMARY KEY (assessment_business_id, task_item_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_assessment_member_loads (
    assessment_business_id VARCHAR(64) NOT NULL,
    employee_business_id VARCHAR(64) NOT NULL,
    employee_snapshot_ref VARCHAR(64) NULL,
    occupied_seconds_before BIGINT NOT NULL,
    available_seconds_before BIGINT NOT NULL,
    occupied_seconds_after BIGINT NOT NULL,
    PRIMARY KEY (assessment_business_id, employee_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_employee_day_loads (
    employee_business_id VARCHAR(64) NOT NULL,
    work_date DATE NOT NULL,
    committed_standard_seconds BIGINT NOT NULL,
    reserved_standard_seconds BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (employee_business_id, work_date),
    CONSTRAINT ck_day_load_nonnegative CHECK (
        committed_standard_seconds >= 0 AND reserved_standard_seconds >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_product_day_capacity_usages (
    product_business_id VARCHAR(64) NOT NULL,
    work_date DATE NOT NULL,
    committed_new_making_quantity INT NOT NULL,
    reserved_new_making_quantity INT NOT NULL,
    capacity_limit INT NOT NULL,
    product_content_version BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (product_business_id, work_date),
    -- 不设“已用 <= 上限”的硬约束：商品上限可能被下调到低于既有已提交数量，
    -- 该状态下必须只阻止新增安排，不能追溯使已完成事实失效（基线 11 §6.6）。
    CONSTRAINT ck_capacity_nonnegative CHECK (
        committed_new_making_quantity >= 0 AND reserved_new_making_quantity >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_active_subject_references (
    task_business_id VARCHAR(64) NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_business_id VARCHAR(64) NOT NULL,
    work_date DATE NOT NULL,
    status VARCHAR(32) NOT NULL,
    PRIMARY KEY (task_business_id),
    KEY ix_active_subject (subject_type, subject_business_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_active_participant_references (
    task_business_id VARCHAR(64) NOT NULL,
    employee_business_id VARCHAR(64) NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_business_id VARCHAR(64) NOT NULL,
    work_date DATE NOT NULL,
    occupied_standard_seconds BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    PRIMARY KEY (task_business_id, employee_business_id),
    KEY ix_active_participant (employee_business_id, work_date, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_employee_day_work_modes (
    employee_business_id VARCHAR(64) NOT NULL,
    work_date DATE NOT NULL,
    work_mode VARCHAR(32) NOT NULL,
    retained_task_count INT NOT NULL DEFAULT 0,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (employee_business_id, work_date),
    CONSTRAINT ck_work_mode CHECK (work_mode IN ('PRODUCTION', 'OTHER_TASK'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_employee_daily_task_slots (
    employee_business_id VARCHAR(64) NOT NULL,
    work_date DATE NOT NULL,
    slot_type VARCHAR(32) NOT NULL,
    task_business_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (employee_business_id, work_date, slot_type),
    CONSTRAINT ck_slot_type CHECK (
        slot_type IN ('NORMAL_PRODUCTION', 'EXTRA_PRODUCTION', 'OTHER_TASK', 'REPAIR')),
    CONSTRAINT ck_slot_status CHECK (status IN ('ACTIVE', 'RETAINED', 'RELEASED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_task_cancellation_facts (
    fact_business_id VARCHAR(64) NOT NULL,
    task_business_id VARCHAR(64) NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    reason_text VARCHAR(500) NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    command_execution_id VARCHAR(64) NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (fact_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
