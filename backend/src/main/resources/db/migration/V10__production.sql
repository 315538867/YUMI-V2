-- 生产模块（阶段五）：生产计划、一次性核验、返工/重做来源、超额预占、工作台提醒、其他排班与工时更正。
-- 表归属见 database-design.md §8，数量口径见 domain-and-quantity-model.md §5–§7/§11，需求见 specs/production-management/spec.md，
-- 施工文档见 docs/architecture/production-module-design.md §3。
-- 事实不可变：核验、来源、预占、工时核验与更正只追加；计划取消只改状态并恢复来源余额，不物理删除。
-- 本迁移只建表与约束，不写入任何数据。

-- 生产计划：类型 + 订单明细 + 工序 + 日期 + 执行员工 + 计划数量
CREATE TABLE production_plans (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    plan_no CHAR(8) NOT NULL,
    -- NORMAL / REWORK / REMAKE / OVERTIME / AFTER_SALES_REWORK / AFTER_SALES_REPLACEMENT
    plan_type VARCHAR(32) NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    order_item_id BIGINT UNSIGNED NOT NULL,
    -- 售后单引用：阶段八接入后写入，本阶段恒 NULL（售后表尚未建立，故不建外键）
    after_sales_case_id BIGINT UNSIGNED NULL,
    node VARCHAR(32) NOT NULL,
    plan_date DATE NOT NULL,
    employee_id BIGINT UNSIGNED NOT NULL,
    employee_name VARCHAR(100) NOT NULL,
    quantity INT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    -- 来源：ORDER（正常）/ REWORK_SOURCE / REMAKE_SOURCE / NONE（超额任务，来源是 overtime_preemptions 集合）
    source_type VARCHAR(32) NOT NULL,
    -- 0 = 来源无明细行；用 0 而非 NULL 以保证唯一键对“无明细来源”同样生效
    source_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    source_line_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    note VARCHAR(500) NULL,
    cancelled_at DATETIME(6) NULL,
    cancelled_by VARCHAR(100) NULL,
    cancel_reason VARCHAR(500) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_production_plans_plan_no (plan_no),
    KEY idx_production_plans_date_node (plan_date, node),
    KEY idx_production_plans_employee (employee_id, plan_date),
    KEY idx_production_plans_item (order_item_id, node),
    KEY idx_production_plans_order (order_id),
    KEY idx_production_plans_status (status),
    KEY idx_production_plans_source (source_type, source_id),
    KEY idx_production_plans_request_id (request_id),
    CONSTRAINT fk_production_plans_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_production_plans_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT fk_production_plans_employee FOREIGN KEY (employee_id) REFERENCES employees (id),
    CONSTRAINT ck_production_plans_quantity CHECK (quantity > 0),
    CONSTRAINT ck_production_plans_status CHECK (status IN ('PENDING', 'VERIFIED', 'CANCELLED')),
    CONSTRAINT ck_production_plans_type CHECK (plan_type IN ('NORMAL', 'REWORK', 'REMAKE', 'OVERTIME',
        'AFTER_SALES_REWORK', 'AFTER_SALES_REPLACEMENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 待执行正常计划的日期/员工/数量/备注调整历史（不产生核验、库存或履约事实）
CREATE TABLE production_plan_adjustments (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    plan_id BIGINT UNSIGNED NOT NULL,
    -- DATE / EMPLOYEE / QUANTITY / NOTE / COMBINED
    adjustment_type VARCHAR(16) NOT NULL,
    before_plan_date DATE NULL,
    after_plan_date DATE NULL,
    before_employee_id BIGINT UNSIGNED NULL,
    before_employee_name VARCHAR(100) NULL,
    after_employee_id BIGINT UNSIGNED NULL,
    after_employee_name VARCHAR(100) NULL,
    before_quantity INT UNSIGNED NULL,
    after_quantity INT UNSIGNED NULL,
    before_note VARCHAR(500) NULL,
    after_note VARCHAR(500) NULL,
    reason VARCHAR(500) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    KEY idx_production_plan_adjustments_plan (plan_id, id),
    KEY idx_production_plan_adjustments_request_id (request_id),
    CONSTRAINT fk_production_plan_adjustments_plan FOREIGN KEY (plan_id) REFERENCES production_plans (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 一次性核验：每计划最多一条有效核验（唯一外键）
CREATE TABLE production_verifications (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    plan_id BIGINT UNSIGNED NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    order_item_id BIGINT UNSIGNED NOT NULL,
    node VARCHAR(32) NOT NULL,
    completed_quantity INT UNSIGNED NOT NULL,
    qualified_quantity INT UNSIGNED NOT NULL,
    rework_quantity INT UNSIGNED NOT NULL,
    scrap_quantity INT UNSIGNED NOT NULL,
    -- 未完成 = 计划数量 − 本次完成（跨表，由应用在同一事务内计算并断言）
    incomplete_quantity INT UNSIGNED NOT NULL,
    verify_note VARCHAR(500) NULL,
    verified_by VARCHAR(100) NOT NULL,
    verified_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    -- 每计划最多一次有效核验
    UNIQUE KEY uk_production_verifications_plan (plan_id),
    KEY idx_production_verifications_item_node (order_item_id, node),
    KEY idx_production_verifications_request_id (request_id),
    CONSTRAINT fk_production_verifications_plan FOREIGN KEY (plan_id) REFERENCES production_plans (id),
    CONSTRAINT fk_production_verifications_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_production_verifications_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    -- 本次完成 = 合格 + 返工 + 报废
    CONSTRAINT ck_production_verifications_equation CHECK (
        completed_quantity = qualified_quantity + rework_quantity + scrap_quantity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 返工来源：原核验 + 发现问题工序 + 目标工序 + 总量/已安排（余额 = 总量 − 已安排）
CREATE TABLE rework_sources (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    verification_id BIGINT UNSIGNED NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    order_item_id BIGINT UNSIGNED NOT NULL,
    found_node VARCHAR(32) NOT NULL,
    target_node VARCHAR(32) NOT NULL,
    total_quantity INT UNSIGNED NOT NULL,
    arranged_quantity INT UNSIGNED NOT NULL DEFAULT 0,
    -- 返工次数：首次返工为 1，返工计划再返工时递增
    round_no INT UNSIGNED NOT NULL DEFAULT 1,
    previous_source_id BIGINT UNSIGNED NULL,
    reason VARCHAR(500) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    -- 同一次核验的同一目标工序只有一条来源（数量在来源内累加，避免重复来源）
    UNIQUE KEY uk_rework_sources_target (verification_id, target_node),
    KEY idx_rework_sources_item (order_item_id),
    KEY idx_rework_sources_previous (previous_source_id),
    KEY idx_rework_sources_request_id (request_id),
    CONSTRAINT fk_rework_sources_verification FOREIGN KEY (verification_id) REFERENCES production_verifications (id),
    CONSTRAINT fk_rework_sources_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_rework_sources_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT fk_rework_sources_previous FOREIGN KEY (previous_source_id) REFERENCES rework_sources (id),
    CONSTRAINT ck_rework_sources_quantity CHECK (total_quantity > 0 AND arranged_quantity <= total_quantity),
    CONSTRAINT ck_rework_sources_round CHECK (round_no > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 报废重做来源：原报废核验 + 报废工序 + 重做起始工序（默认等于报废工序）
CREATE TABLE remake_sources (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    verification_id BIGINT UNSIGNED NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    order_item_id BIGINT UNSIGNED NOT NULL,
    scrap_node VARCHAR(32) NOT NULL,
    start_node VARCHAR(32) NOT NULL,
    total_quantity INT UNSIGNED NOT NULL,
    arranged_quantity INT UNSIGNED NOT NULL DEFAULT 0,
    -- start_node = MAKING 时必填（前序材料不可用等），由应用校验
    reason VARCHAR(500) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    -- 同一次核验的同一重做起始工序只有一条来源（与返工对称：默认从报废工序开始，显式选择更早工序时另建来源）
    UNIQUE KEY uk_remake_sources_target (verification_id, start_node),
    KEY idx_remake_sources_item (order_item_id),
    KEY idx_remake_sources_request_id (request_id),
    CONSTRAINT fk_remake_sources_verification FOREIGN KEY (verification_id) REFERENCES production_verifications (id),
    CONSTRAINT fk_remake_sources_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_remake_sources_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT ck_remake_sources_quantity CHECK (total_quantity > 0 AND arranged_quantity <= total_quantity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 超额预占：超额任务从未来正常计划预占数量；不修改未来计划原始数量
CREATE TABLE overtime_preemptions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    overtime_plan_id BIGINT UNSIGNED NOT NULL,
    future_plan_id BIGINT UNSIGNED NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    order_item_id BIGINT UNSIGNED NOT NULL,
    node VARCHAR(32) NOT NULL,
    preempted_quantity INT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    released_at DATETIME(6) NULL,
    released_by VARCHAR(100) NULL,
    release_reason VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    -- 同一超额任务对同一未来计划只有一条预占
    UNIQUE KEY uk_overtime_preemptions_pair (overtime_plan_id, future_plan_id),
    -- 有效预占合计的加锁索引
    KEY idx_overtime_preemptions_future (future_plan_id, status),
    KEY idx_overtime_preemptions_item (order_item_id),
    KEY idx_overtime_preemptions_request_id (request_id),
    CONSTRAINT fk_overtime_preemptions_overtime FOREIGN KEY (overtime_plan_id) REFERENCES production_plans (id),
    CONSTRAINT fk_overtime_preemptions_future FOREIGN KEY (future_plan_id) REFERENCES production_plans (id),
    CONSTRAINT fk_overtime_preemptions_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_overtime_preemptions_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT ck_overtime_preemptions_quantity CHECK (preempted_quantity > 0),
    CONSTRAINT ck_overtime_preemptions_status CHECK (status IN ('ACTIVE', 'RELEASED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 工作台提醒：只辅助工作台，不是数量事实来源（数量以计划/核验/来源/预占为准，提醒可重建）
CREATE TABLE production_reminders (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    -- INCOMPLETE / OVERTIME_PENDING_VERIFY / PLAN_ADJUSTMENT
    reminder_type VARCHAR(32) NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    order_item_id BIGINT UNSIGNED NOT NULL,
    node VARCHAR(32) NOT NULL,
    plan_id BIGINT UNSIGNED NULL,
    verification_id BIGINT UNSIGNED NULL,
    preemption_id BIGINT UNSIGNED NULL,
    -- 计划待调整时指向受影响的未来计划
    future_plan_id BIGINT UNSIGNED NULL,
    quantity INT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    -- RESCHEDULED / PARTIAL / DEFERRED / ADJUSTED / NO_ADJUSTMENT
    handling_type VARCHAR(16) NULL,
    handled_quantity INT UNSIGNED NULL,
    reason VARCHAR(500) NULL,
    handled_by VARCHAR(100) NULL,
    handled_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    KEY idx_production_reminders_type_status (reminder_type, status),
    KEY idx_production_reminders_item (order_item_id, node),
    KEY idx_production_reminders_plan (plan_id),
    KEY idx_production_reminders_future_plan (future_plan_id, status),
    KEY idx_production_reminders_request_id (request_id),
    CONSTRAINT fk_production_reminders_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_production_reminders_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT fk_production_reminders_plan FOREIGN KEY (plan_id) REFERENCES production_plans (id),
    CONSTRAINT fk_production_reminders_verification FOREIGN KEY (verification_id) REFERENCES production_verifications (id),
    CONSTRAINT fk_production_reminders_preemption FOREIGN KEY (preemption_id) REFERENCES overtime_preemptions (id),
    CONSTRAINT fk_production_reminders_future_plan FOREIGN KEY (future_plan_id) REFERENCES production_plans (id),
    CONSTRAINT ck_production_reminders_status CHECK (status IN ('OPEN', 'HANDLED')),
    CONSTRAINT ck_production_reminders_type CHECK (reminder_type IN ('INCOMPLETE', 'OVERTIME_PENDING_VERIFY',
        'PLAN_ADJUSTMENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 其他排班：只保存总分钟与工时事实，不产生商品、库存或订单履约数量
CREATE TABLE other_schedules (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    schedule_no CHAR(8) NOT NULL,
    schedule_date DATE NOT NULL,
    employee_id BIGINT UNSIGNED NOT NULL,
    employee_name VARCHAR(100) NOT NULL,
    hours INT UNSIGNED NOT NULL,
    minutes INT UNSIGNED NOT NULL,
    total_minutes INT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    note VARCHAR(500) NULL,
    cancelled_at DATETIME(6) NULL,
    cancelled_by VARCHAR(100) NULL,
    cancel_reason VARCHAR(500) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_other_schedules_schedule_no (schedule_no),
    KEY idx_other_schedules_date_employee (schedule_date, employee_id),
    KEY idx_other_schedules_status (status),
    KEY idx_other_schedules_request_id (request_id),
    CONSTRAINT fk_other_schedules_employee FOREIGN KEY (employee_id) REFERENCES employees (id),
    CONSTRAINT ck_other_schedules_status CHECK (status IN ('PENDING', 'VERIFIED', 'CANCELLED')),
    -- 分钟 0–59，总分钟 = 小时 × 60 + 分钟 且必须大于 0
    CONSTRAINT ck_other_schedules_minutes CHECK (minutes BETWEEN 0 AND 59),
    CONSTRAINT ck_other_schedules_total CHECK (total_minutes = hours * 60 + minutes AND total_minutes > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 其他排班一次性工时核验：每排班最多一条有效核验
CREATE TABLE other_schedule_verifications (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    schedule_id BIGINT UNSIGNED NOT NULL,
    total_minutes INT UNSIGNED NOT NULL,
    note VARCHAR(500) NULL,
    verified_by VARCHAR(100) NOT NULL,
    verified_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_other_schedule_verifications_schedule (schedule_id),
    KEY idx_other_schedule_verifications_request_id (request_id),
    CONSTRAINT fk_other_schedule_verifications_schedule FOREIGN KEY (schedule_id) REFERENCES other_schedules (id),
    CONSTRAINT ck_other_schedule_verifications_total CHECK (total_minutes > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 其他排班工时更正：不修改原核验，只追加更正事实（有效工时 = 原核验 + 最新更正）
CREATE TABLE other_schedule_time_corrections (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    verification_id BIGINT UNSIGNED NOT NULL,
    schedule_id BIGINT UNSIGNED NOT NULL,
    before_total_minutes INT UNSIGNED NOT NULL,
    after_total_minutes INT UNSIGNED NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    KEY idx_other_schedule_time_corrections_verification (verification_id, id),
    KEY idx_other_schedule_time_corrections_schedule (schedule_id),
    KEY idx_other_schedule_time_corrections_request_id (request_id),
    CONSTRAINT fk_other_schedule_time_corrections_verification FOREIGN KEY (verification_id)
        REFERENCES other_schedule_verifications (id),
    CONSTRAINT fk_other_schedule_time_corrections_schedule FOREIGN KEY (schedule_id) REFERENCES other_schedules (id),
    CONSTRAINT ck_other_schedule_time_corrections_after CHECK (after_total_minutes > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
