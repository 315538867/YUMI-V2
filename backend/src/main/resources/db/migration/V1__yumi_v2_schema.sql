-- YUMI V2 单一基线迁移（2026-09-25 压缩）
--
-- 开发期未上线、未做部署测试，用户确认「把所有 Flyway 版本压缩到一个」并「直接清库重建」，
-- 故把原 V1–V14 按版本顺序合并为本文件；章节横幅保留原版本号与文件名，便于对照历史证据
-- （openspec/changes/build-yumi-v2-order-fulfillment/tasks.md 中各阶段「Flyway 版本」条目）。
--
-- 全库表结构与种子数据均以本文件为唯一权威；Hibernate ddl-auto=validate 常绿。
-- 迁移语义：空库执行本文件即得到完整结构（含静态数据与全局参数种子）。
--

-- ==========================================================================================
-- 原 V1__foundation.sql
-- ==========================================================================================

CREATE TABLE admin_accounts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    linked_employee_id BIGINT UNSIGNED NULL,
    failed_login_count INT UNSIGNED NOT NULL DEFAULT 0,
    locked_until DATETIME(6) NULL,
    last_login_at DATETIME(6) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_accounts_username (username),
    KEY idx_admin_accounts_request_id (request_id),
    KEY idx_admin_accounts_idempotency_key (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE number_sequences (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    sequence_key VARCHAR(100) NOT NULL,
    current_value BIGINT UNSIGNED NOT NULL DEFAULT 0,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_number_sequences_sequence_key (sequence_key),
    KEY idx_number_sequences_request_id (request_id),
    KEY idx_number_sequences_idempotency_key (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE file_metadata (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    object_key VARCHAR(512) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    content_length BIGINT UNSIGNED NOT NULL,
    sha256 CHAR(64) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_metadata_object_key (object_key),
    UNIQUE KEY uk_file_metadata_sha256 (sha256),
    KEY idx_file_metadata_request_id (request_id),
    KEY idx_file_metadata_idempotency_key (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ==========================================================================================
-- 原 V2__idempotency.sql
-- ==========================================================================================

CREATE TABLE idempotency_records (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    idempotency_key VARCHAR(128) NOT NULL,
    admin_username VARCHAR(100) NULL,
    http_method VARCHAR(10) NOT NULL,
    path VARCHAR(255) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    response_status SMALLINT UNSIGNED NOT NULL,
    response_content_type VARCHAR(128) NULL,
    response_body MEDIUMTEXT NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_records_key (idempotency_key),
    KEY idx_idempotency_records_request_id (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ==========================================================================================
-- 原 V3__audit_logs.sql
-- ==========================================================================================

CREATE TABLE audit_logs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    admin_username VARCHAR(100) NULL,
    request_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NULL,
    business_no VARCHAR(64) NULL,
    http_method VARCHAR(10) NOT NULL,
    path VARCHAR(255) NOT NULL,
    result VARCHAR(16) NOT NULL,
    response_status SMALLINT UNSIGNED NOT NULL,
    error_code VARCHAR(64) NULL,
    occurred_at DATETIME(6) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_logs_request_id (request_id),
    KEY idx_audit_logs_business_no (business_no),
    KEY idx_audit_logs_occurred_at (occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ==========================================================================================
-- 原 V4__catalog.sql
-- ==========================================================================================

-- 基础资料与静态数据（未上线阶段已重写为最终结构，清库重建；见 static-data-and-product-fields-design.md §4）
CREATE TABLE star_levels (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(20) NOT NULL,
    std_minutes INT UNSIGNED NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_star_levels_name (name),
    KEY idx_star_levels_request_id (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 缝边种类：与星级、包装档位同构，只提供「标准分钟」；单件缝边人工成本 = 标准分钟 × 全局时薪 ÷ 60。
-- 缝边收费不在此表：由商品「缝边价格」预填、订单明细行手填
CREATE TABLE seam_types (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    std_minutes INT UNSIGNED NOT NULL DEFAULT 0,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_seam_types_name (name),
    KEY idx_seam_types_request_id (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 员工工种：系统预置四道工序，code 由系统固定、名称可改，不提供新增、删除与停用
CREATE TABLE work_types (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_work_types_code (code),
    UNIQUE KEY uk_work_types_name (name),
    KEY idx_work_types_request_id (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 包装档位：只提供标准分钟，提成改由商品持有
CREATE TABLE packaging_tiers (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tier_name VARCHAR(50) NOT NULL,
    std_minutes INT UNSIGNED NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_packaging_tiers_name (tier_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE catalog_settings (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    setting_key VARCHAR(64) NOT NULL,
    setting_value DECIMAL(19,6) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_catalog_settings_key (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 全局参数默认值：取自清库前库内实际值（2026-09-25 用户确认「全局参数在 SQL 中加默认值、读取现在表里的」），
-- 避免重建后丢掉已录入的真实参数。金额类 scale4/scale6，百分比类存百分数（20 = 20%）。
INSERT INTO catalog_settings (setting_key, setting_value, created_at, updated_at) VALUES
    ('glue_unit_price', 0.034000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('colorpaste_unit_price', 0.002400, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('loss_rate_default', 20.000000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('box_labor_default', 0.500000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('transport_packing_default', 0.200000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('sundries_default', 0.500000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('rent_utilities_default', 0.800000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

-- 商品：自身属性 + 全局口径快照 + 静态数据引用快照。
-- 缝边只保存「默认缝边剪袋类型（可空＝默认不缝边剪袋）+ 缝边价格」作为订单缝边定制的默认值，
-- 不保存缝边数量或缝边成本；总成本与参考售价按不缝边剪袋口径保存
CREATE TABLE products (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    product_no CHAR(6) NOT NULL,
    name VARCHAR(200) NOT NULL,
    note VARCHAR(1000) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    image_file_id BIGINT UNSIGNED NULL,
    star_level_id BIGINT UNSIGNED NOT NULL,
    star_name VARCHAR(20) NOT NULL,
    star_std_minutes INT UNSIGNED NOT NULL,
    sale_price DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    weight_g INT UNSIGNED NOT NULL DEFAULT 0,
    loss_rate DECIMAL(9,6) NOT NULL DEFAULT 0.000000,
    glue_unit_price DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    glue_grams INT UNSIGNED NOT NULL DEFAULT 0,
    glue_cost DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    colorpaste_unit_price DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    colorpaste_cost DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    qty_8h INT UNSIGNED NOT NULL DEFAULT 0,
    qty_6h INT UNSIGNED NOT NULL DEFAULT 0,
    product_labor_fee DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    packaging_tier_id BIGINT UNSIGNED NULL,
    packaging_tier_name VARCHAR(50) NULL,
    packaging_std_minutes INT UNSIGNED NULL,
    packaging_commission DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    packaging_labor_fee DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    seam_type_id BIGINT UNSIGNED NULL,
    seam_type_name VARCHAR(100) NULL,
    -- 所选缝边种类的标准分钟快照（商品只提供默认值，缝边数量与收费仍由订单决定）
    seam_std_minutes INT UNSIGNED NULL,
    -- 单件缝边人工成本快照（= seam_std_minutes × 保存当时全局时薪 ÷ 60），与缝边收费无关
    seam_unit_cost DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    seam_fee DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    box_labor_fee DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    transport_packing_fee DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    daily_sundries_fee DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    rent_utilities_fee DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    mold_amort_fee DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    material_cost DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    labor_cost DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    other_cost DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    total_cost DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    reference_price DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    -- 生产产能（阶段五 5.1）：同一模具批次可并行生产数量、每日允许批次数，均为正整数；
    -- 最大日容量 = 模具数量 × 每日批次数，由服务端派生，不落库
    mold_quantity INT UNSIGNED NOT NULL,
    daily_batch_limit INT UNSIGNED NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_products_product_no (product_no),
    KEY idx_products_status (status),
    KEY idx_products_name (name),
    KEY idx_products_star_level_id (star_level_id),
    KEY idx_products_request_id (request_id),
    KEY idx_products_image_file (image_file_id),
    KEY idx_products_seam_type_id (seam_type_id),
    CONSTRAINT ck_products_mold_quantity CHECK (mold_quantity > 0),
    CONSTRAINT ck_products_daily_batch_limit CHECK (daily_batch_limit > 0),
    CONSTRAINT fk_products_image_file FOREIGN KEY (image_file_id) REFERENCES file_metadata (id),
    CONSTRAINT fk_products_seam_type FOREIGN KEY (seam_type_id) REFERENCES seam_types (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE customers (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    customer_no CHAR(6) NOT NULL,
    name VARCHAR(100) NOT NULL,
    contact VARCHAR(100) NULL,
    phone VARCHAR(30) NULL,
    note VARCHAR(1000) NULL,
    default_recipient VARCHAR(100) NOT NULL DEFAULT '',
    default_recipient_phone VARCHAR(30) NOT NULL DEFAULT '',
    default_region VARCHAR(200) NOT NULL DEFAULT '',
    default_address VARCHAR(500) NOT NULL DEFAULT '',
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_customers_customer_no (customer_no),
    KEY idx_customers_name (name),
    KEY idx_customers_phone (phone),
    KEY idx_customers_request_id (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE employees (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    employee_no CHAR(6) NOT NULL,
    name VARCHAR(100) NOT NULL,
    phone VARCHAR(30) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    first_hire_date DATE NULL,
    note VARCHAR(1000) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_employees_employee_no (employee_no),
    KEY idx_employees_status (status),
    KEY idx_employees_request_id (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE employee_work_types (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    employee_id BIGINT UNSIGNED NOT NULL,
    work_type_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_employee_work_types_employee_type (employee_id, work_type_id),
    KEY idx_employee_work_types_work_type (work_type_id),
    CONSTRAINT fk_employee_work_types_employee FOREIGN KEY (employee_id) REFERENCES employees (id),
    CONSTRAINT fk_employee_work_types_work_type FOREIGN KEY (work_type_id) REFERENCES work_types (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE employee_employment_events (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    employee_id BIGINT UNSIGNED NOT NULL,
    event_type VARCHAR(16) NOT NULL,
    event_date DATE NOT NULL,
    reason VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    PRIMARY KEY (id),
    KEY idx_employee_employment_events_employee (employee_id, event_type),
    CONSTRAINT fk_employee_employment_events_employee FOREIGN KEY (employee_id) REFERENCES employees (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE master_data_change_logs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    entity_type VARCHAR(32) NOT NULL,
    entity_id BIGINT UNSIGNED NOT NULL,
    business_no VARCHAR(16) NOT NULL,
    before_json JSON NULL,
    after_json JSON NOT NULL,
    reason VARCHAR(500) NULL,
    admin_username VARCHAR(100) NULL,
    request_id VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_master_data_change_logs_entity (entity_type, entity_id),
    KEY idx_master_data_change_logs_business_no (business_no),
    KEY idx_master_data_change_logs_request_id (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ==========================================================================================
-- 原 V5__static_data_seed.sql
-- ==========================================================================================

-- 静态数据种子（未上线阶段重写；类别 code 由系统固定，条目数据可由用户维护）
INSERT INTO star_levels (name, std_minutes, created_at, updated_at) VALUES
    ('一星', 5, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('二星', 10, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('三星', 15, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('四星', 20, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('五星', 30, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

-- 员工工种：四道工序的 code 由系统固定，名称可改，不允许新增、删除或停用
INSERT INTO work_types (code, name, created_at, updated_at) VALUES
    ('MAKING', '制作', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('PACKING_BAG', '捏毛装袋', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('SEAM_CUTTING', '缝边剪袋', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('OTHER', '其他', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

-- 包装档位：系统预置 6 分钟档（对应真实核算表固定的「6 分钟 × 时薪 ÷ 60 + 商品提成」），其余档位由用户自建
INSERT INTO packaging_tiers (tier_name, std_minutes, created_at, updated_at) VALUES
    ('6 分钟档', 6.000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

-- ==========================================================================================
-- 原 V6__packaging_commission_default.sql
-- ==========================================================================================

-- 追加的全局参数（元/件、元/小时、小时、比例、百分数）：默认值取自清库前库内实际值
-- （2026-09-25 用户确认「全局参数在 SQL 中加默认值、读取现在表里的」）
-- 1) 包装提成：商品表单取此全局值作默认、可修改（对应真实核算表的「打包提成」参数）
-- 2) 时薪：制品/包装/缝边三类人工费的统一派生基数
-- 3) 工作日小时数：制品日薪 = 时薪 × 该值；工作日标准数量 = floor(该值 × 60 ÷ 星级标准分钟)
-- 4) 制品有效工时率：正常工作时间由「工作日小时数」给出，但星级只影响制品工序、
--    制品工序不可能排满整个工作日，故制品人工费按有效工时产出计
--    （有效工时产量 = floor(工作日小时数 × 60 × 该率 ÷ 星级标准分钟)）
-- 5) 目标利润率（百分数，30 = 30%）：参考售价 = 单件总成本 ÷ (1 − 该率)
INSERT INTO catalog_settings (setting_key, setting_value, created_at, updated_at) VALUES
    ('packaging_commission_default', 0.500000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('hourly_wage', 15.000000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('workday_hours', 8.000000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('making_effective_hour_rate', 0.750000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('target_margin_rate', 30.000000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

-- ==========================================================================================
-- 原 V7__orders.sql
-- ==========================================================================================

-- 订单模块（阶段三）：订单核心、确认快照、变更单与履约台账。
-- 表归属见 database-design.md §5–6，数量口径见 domain-and-quantity-model.md，列级设计见 order-module-design.md §3。
-- 金额 DECIMAL(19,4)、比例 DECIMAL(9,6)、数量 INT UNSIGNED（非负）；事实表不提供 UPDATE/DELETE 入口。
-- 为阶段四–九预留的列本阶段只建列与约束、不写入。

-- 订单主表：当前有效订单数据 + 金额汇总 + 确认/取消/关闭信息
CREATE TABLE orders (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_no CHAR(7) NOT NULL,
    customer_id BIGINT UNSIGNED NOT NULL,
    customer_name VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    order_date DATE NOT NULL,
    expected_delivery_date DATE NULL,
    recipient_name VARCHAR(100) NOT NULL,
    recipient_phone VARCHAR(32) NOT NULL,
    region VARCHAR(100) NOT NULL,
    address VARCHAR(300) NOT NULL,
    note VARCHAR(1000) NULL,
    goods_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    seam_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    discount_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    receivable_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    goods_cost_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    seam_cost_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    cost_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    profit_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    confirmed_at DATETIME(6) NULL,
    confirmed_by VARCHAR(100) NULL,
    cancelled_at DATETIME(6) NULL,
    cancelled_by VARCHAR(100) NULL,
    cancel_reason VARCHAR(500) NULL,
    closed_at DATETIME(6) NULL,
    closed_by VARCHAR(100) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_orders_order_no (order_no),
    KEY idx_orders_status (status),
    KEY idx_orders_customer (customer_id),
    KEY idx_orders_order_date (order_date),
    KEY idx_orders_request_id (request_id),
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT ck_orders_discount_not_negative CHECK (discount_amount >= 0),
    CONSTRAINT ck_orders_discount_within_goods CHECK (discount_amount <= goods_amount + seam_amount),
    CONSTRAINT ck_orders_amounts_not_negative CHECK (goods_amount >= 0 AND seam_amount >= 0
        AND receivable_amount >= 0 AND goods_cost_amount >= 0 AND seam_cost_amount >= 0 AND cost_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 订单明细当前有效值：Q/E、成交价、缝边定制与成本快照
CREATE TABLE order_items (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id BIGINT UNSIGNED NOT NULL,
    line_no INT UNSIGNED NOT NULL,
    product_id BIGINT UNSIGNED NOT NULL,
    product_no CHAR(6) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    quantity INT UNSIGNED NOT NULL DEFAULT 0,
    seam_quantity INT UNSIGNED NOT NULL DEFAULT 0,
    unit_price DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    goods_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    seam_type_id BIGINT UNSIGNED NULL,
    seam_type_name VARCHAR(100) NULL,
    seam_unit_cost DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    seam_fee DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    seam_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    unit_cost DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    goods_cost_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    seam_cost_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    note VARCHAR(500) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_items_order_line (order_id, line_no),
    KEY idx_order_items_order (order_id),
    KEY idx_order_items_product (product_id),
    KEY idx_order_items_seam_type (seam_type_id),
    KEY idx_order_items_request_id (request_id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_order_items_seam_type FOREIGN KEY (seam_type_id) REFERENCES seam_types (id),
    CONSTRAINT ck_order_items_seam_within_quantity CHECK (seam_quantity <= quantity),
    CONSTRAINT ck_order_items_amounts_not_negative CHECK (unit_price >= 0 AND goods_amount >= 0
        AND seam_unit_cost >= 0 AND seam_fee >= 0 AND seam_amount >= 0
        AND unit_cost >= 0 AND goods_cost_amount >= 0 AND seam_cost_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 订单级确认快照：客户、收货、金额与确认信息，确认后不可修改
CREATE TABLE order_confirmation_snapshots (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id BIGINT UNSIGNED NOT NULL,
    customer_id BIGINT UNSIGNED NOT NULL,
    customer_no CHAR(6) NOT NULL,
    customer_name VARCHAR(200) NOT NULL,
    contact VARCHAR(100) NULL,
    phone VARCHAR(32) NULL,
    recipient_name VARCHAR(100) NOT NULL,
    recipient_phone VARCHAR(32) NOT NULL,
    region VARCHAR(100) NOT NULL,
    address VARCHAR(300) NOT NULL,
    note VARCHAR(1000) NULL,
    goods_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    seam_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    discount_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    receivable_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    goods_cost_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    seam_cost_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    cost_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    profit_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    confirmed_by VARCHAR(100) NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_confirmation_snapshots_order (order_id),
    KEY idx_order_confirmation_snapshots_request_id (request_id),
    CONSTRAINT fk_order_confirmation_snapshots_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 明细级确认快照：商品识别/星级/成本组成/成交价/数量/缝边参数/冻结流程
CREATE TABLE order_item_snapshots (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id BIGINT UNSIGNED NOT NULL,
    order_item_id BIGINT UNSIGNED NOT NULL,
    line_no INT UNSIGNED NOT NULL,
    product_id BIGINT UNSIGNED NOT NULL,
    product_no CHAR(6) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    product_note VARCHAR(1000) NULL,
    image_file_id BIGINT UNSIGNED NULL,
    star_level_id BIGINT UNSIGNED NULL,
    star_name VARCHAR(20) NULL,
    star_std_minutes INT UNSIGNED NULL,
    packaging_std_minutes INT UNSIGNED NULL,
    quantity INT UNSIGNED NOT NULL,
    seam_quantity INT UNSIGNED NOT NULL DEFAULT 0,
    unit_price DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    goods_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    seam_type_id BIGINT UNSIGNED NULL,
    seam_type_name VARCHAR(100) NULL,
    seam_std_minutes INT UNSIGNED NULL,
    seam_unit_cost DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    seam_fee DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    seam_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    -- 生产参数快照（阶段五 5.1）：确认时冻结；生产域只读本快照，后续商品或全局设置变更不回溯
    making_effective_hour_rate DECIMAL(9,6) NOT NULL,
    workday_hours DECIMAL(19,4) NOT NULL,
    mold_quantity INT UNSIGNED NOT NULL,
    daily_batch_limit INT UNSIGNED NOT NULL,
    unit_cost DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    goods_cost_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    seam_cost_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    glue_grams INT UNSIGNED NOT NULL DEFAULT 0,
    glue_cost DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    colorpaste_cost DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    material_cost DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    product_labor_fee DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    packaging_labor_fee DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    box_labor_fee DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    labor_cost DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    other_cost DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    total_cost DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    flow VARCHAR(200) NOT NULL,
    note VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_item_snapshots_item (order_item_id),
    KEY idx_order_item_snapshots_order (order_id),
    KEY idx_order_item_snapshots_product (product_id),
    KEY idx_order_item_snapshots_request_id (request_id),
    CONSTRAINT fk_order_item_snapshots_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_order_item_snapshots_item FOREIGN KEY (order_item_id) REFERENCES order_items (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 变更单：已确认订单的表头变更草稿与确认信息
CREATE TABLE order_change_orders (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    change_no CHAR(7) NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    reason VARCHAR(500) NULL,
    new_expected_delivery_date DATE NULL,
    new_recipient_name VARCHAR(100) NULL,
    new_recipient_phone VARCHAR(32) NULL,
    new_region VARCHAR(100) NULL,
    new_address VARCHAR(300) NULL,
    new_note VARCHAR(1000) NULL,
    new_discount_amount DECIMAL(19,4) NULL,
    confirmed_by VARCHAR(100) NULL,
    confirmed_at DATETIME(6) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_change_orders_change_no (change_no),
    KEY idx_order_change_orders_order (order_id),
    KEY idx_order_change_orders_status (status),
    KEY idx_order_change_orders_request_id (request_id),
    CONSTRAINT fk_order_change_orders_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_order_change_orders_new_discount_not_negative CHECK (new_discount_amount IS NULL
        OR new_discount_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 变更明细：结构化前后值 + 减单超出数量处理方案（不能只保存文本 diff）
CREATE TABLE order_change_items (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    change_order_id BIGINT UNSIGNED NOT NULL,
    order_item_id BIGINT UNSIGNED NULL,
    change_type VARCHAR(16) NOT NULL,
    line_no INT UNSIGNED NULL,
    product_id BIGINT UNSIGNED NULL,
    before_quantity INT UNSIGNED NULL,
    after_quantity INT UNSIGNED NULL,
    before_seam_quantity INT UNSIGNED NULL,
    after_seam_quantity INT UNSIGNED NULL,
    before_unit_price DECIMAL(19,4) NULL,
    after_unit_price DECIMAL(19,4) NULL,
    before_seam_type_id BIGINT UNSIGNED NULL,
    after_seam_type_id BIGINT UNSIGNED NULL,
    before_seam_fee DECIMAL(19,4) NULL,
    after_seam_fee DECIMAL(19,4) NULL,
    before_note VARCHAR(500) NULL,
    after_note VARCHAR(500) NULL,
    surplus_disposition VARCHAR(24) NULL,
    surplus_quantity INT UNSIGNED NULL,
    surplus_reason VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    KEY idx_order_change_items_change_order (change_order_id),
    KEY idx_order_change_items_order_item (order_item_id),
    KEY idx_order_change_items_request_id (request_id),
    CONSTRAINT fk_order_change_items_change_order FOREIGN KEY (change_order_id) REFERENCES order_change_orders (id),
    CONSTRAINT fk_order_change_items_order_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT ck_order_change_items_target CHECK (
        (change_type = 'ADD' AND order_item_id IS NULL AND product_id IS NOT NULL)
        OR (change_type IN ('UPDATE', 'REMOVE') AND order_item_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 履约事实：不可变；同一来源记录+来源明细+节点+方向只能入账一次
CREATE TABLE fulfillment_entries (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id BIGINT UNSIGNED NOT NULL,
    order_item_id BIGINT UNSIGNED NOT NULL,
    entry_type VARCHAR(32) NOT NULL,
    node VARCHAR(32) NOT NULL,
    direction VARCHAR(8) NOT NULL,
    quantity INT UNSIGNED NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id BIGINT UNSIGNED NOT NULL,
    -- 0 = 该来源无明细行；用 0 而非 NULL 以保证唯一键对“无明细来源”同样生效
    source_line_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    business_date DATE NOT NULL,
    operator_username VARCHAR(100) NULL,
    reverses_entry_id BIGINT UNSIGNED NULL,
    note VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fulfillment_entries_source (source_type, source_id, source_line_id, node, direction),
    KEY idx_fulfillment_entries_item (order_item_id, node, source_type),
    KEY idx_fulfillment_entries_order (order_id),
    KEY idx_fulfillment_entries_reverses (reverses_entry_id),
    KEY idx_fulfillment_entries_request_id (request_id),
    CONSTRAINT fk_fulfillment_entries_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_fulfillment_entries_item FOREIGN KEY (order_item_id) REFERENCES order_items (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 明细当前数量投影：只能由领域服务在写 fulfillment_entries 的同一事务内更新
CREATE TABLE order_item_fulfillment_balances (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id BIGINT UNSIGNED NOT NULL,
    order_item_id BIGINT UNSIGNED NOT NULL,
    required_quantity INT UNSIGNED NOT NULL DEFAULT 0,
    making_inflow INT UNSIGNED NOT NULL DEFAULT 0,
    packing_inflow INT UNSIGNED NOT NULL DEFAULT 0,
    seam_inflow INT UNSIGNED NOT NULL DEFAULT 0,
    making_planned INT UNSIGNED NOT NULL DEFAULT 0,
    packing_planned INT UNSIGNED NOT NULL DEFAULT 0,
    seam_planned INT UNSIGNED NOT NULL DEFAULT 0,
    verified_processed INT UNSIGNED NOT NULL DEFAULT 0,
    rework_pending INT UNSIGNED NOT NULL DEFAULT 0,
    shippable_quantity INT UNSIGNED NOT NULL DEFAULT 0,
    shipped_quantity INT UNSIGNED NOT NULL DEFAULT 0,
    finished_surplus_quantity INT UNSIGNED NOT NULL DEFAULT 0,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_item_fulfillment_balances_item (order_item_id),
    KEY idx_order_item_fulfillment_balances_order (order_id),
    KEY idx_order_item_fulfillment_balances_request_id (request_id),
    CONSTRAINT fk_order_item_fulfillment_balances_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_order_item_fulfillment_balances_item FOREIGN KEY (order_item_id) REFERENCES order_items (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ==========================================================================================
-- 原 V8__inventory.sql
-- ==========================================================================================

-- 库存模块（阶段四）：批次、流水、领用。
-- 表归属见 database-design.md §7，数量口径见 domain-and-quantity-model.md §8/§9，需求见 specs/inventory-management/spec.md。
-- 批次不绑定目标订单；当前数量由不可变流水决定（有效流水行汇总必须等于批次当前数量）；
-- 已生效流水不可编辑/删除，更正一律走冲销流水并保留关联历史。

-- 库存批次：商品 + 已完成工序 + 缝边状态 + 当前数量缓存
CREATE TABLE inventory_batches (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    batch_no CHAR(8) NOT NULL,
    product_id BIGINT UNSIGNED NOT NULL,
    product_no CHAR(6) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    -- 来源业务：OPENING（期初）/ ADJUSTMENT（盘点）/ ALLOCATION_CANCEL（领用取消回补）/ PRODUCTION（阶段五）
    source_type VARCHAR(32) NOT NULL,
    source_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    -- 0 = 来源无明细行；用 0 而非 NULL 以保证唯一键对“无明细来源”同样生效
    source_line_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    node VARCHAR(32) NOT NULL,
    seam_state VARCHAR(16) NOT NULL DEFAULT 'NONE',
    quantity INT UNSIGNED NOT NULL DEFAULT 0,
    inventory_date DATE NOT NULL,
    note VARCHAR(500) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inventory_batches_batch_no (batch_no),
    -- 同一来源只能形成一条批次
    UNIQUE KEY uk_inventory_batches_source (source_type, source_id, source_line_id),
    -- 领用按 商品 + 工序 + 缝边状态 顺序锁批次的锁定索引（配合 SELECT ... FOR UPDATE 做 FIFO）
    KEY idx_inventory_batches_pick (product_id, node, seam_state, id),
    KEY idx_inventory_batches_request_id (request_id),
    CONSTRAINT fk_inventory_batches_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT ck_inventory_batches_quantity_not_negative CHECK (quantity >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 库存流水（业务头）：一次业务操作一个流水，不可变
CREATE TABLE inventory_movements (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    movement_no CHAR(8) NOT NULL,
    -- OPENING / ADJUSTMENT / ALLOCATION / ALLOCATION_CANCEL / REVERSAL
    movement_type VARCHAR(32) NOT NULL,
    business_date DATE NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    source_line_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    reverses_movement_id BIGINT UNSIGNED NULL,
    reason VARCHAR(500) NULL,
    operator_username VARCHAR(100) NULL,
    note VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inventory_movements_movement_no (movement_no),
    KEY idx_inventory_movements_batch_source (source_type, source_id),
    KEY idx_inventory_movements_reverses (reverses_movement_id),
    KEY idx_inventory_movements_request_id (request_id),
    CONSTRAINT fk_inventory_movements_reverses FOREIGN KEY (reverses_movement_id) REFERENCES inventory_movements (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 库存流水行：批次、方向、数量与变动前后数量
CREATE TABLE inventory_movement_lines (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    movement_id BIGINT UNSIGNED NOT NULL,
    batch_id BIGINT UNSIGNED NOT NULL,
    direction VARCHAR(8) NOT NULL,
    quantity INT UNSIGNED NOT NULL,
    quantity_before INT UNSIGNED NOT NULL,
    quantity_after INT UNSIGNED NOT NULL,
    product_id BIGINT UNSIGNED NOT NULL,
    node VARCHAR(32) NOT NULL,
    seam_state VARCHAR(16) NOT NULL,
    order_item_id BIGINT UNSIGNED NULL,
    note VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    KEY idx_inventory_movement_lines_movement (movement_id),
    KEY idx_inventory_movement_lines_batch (batch_id, id),
    KEY idx_inventory_movement_lines_order_item (order_item_id),
    KEY idx_inventory_movement_lines_request_id (request_id),
    CONSTRAINT fk_inventory_movement_lines_movement FOREIGN KEY (movement_id) REFERENCES inventory_movements (id),
    CONSTRAINT fk_inventory_movement_lines_batch FOREIGN KEY (batch_id) REFERENCES inventory_batches (id),
    CONSTRAINT fk_inventory_movement_lines_order_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    -- 非负与方向一致性：入库 after = before + qty，出库 after + qty = before
    CONSTRAINT ck_inventory_movement_lines_quantity CHECK (quantity > 0),
    CONSTRAINT ck_inventory_movement_lines_direction CHECK (
        (direction = 'IN' AND quantity_after = quantity_before + quantity)
        OR (direction = 'OUT' AND quantity_after + quantity = quantity_before))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 库存领用业务头：一个订单一次领用（可含多条批次明细）
CREATE TABLE inventory_allocations (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED',
    reason VARCHAR(500) NULL,
    cancelled_at DATETIME(6) NULL,
    cancelled_by VARCHAR(100) NULL,
    cancel_reason VARCHAR(500) NULL,
    reverses_allocation_id BIGINT UNSIGNED NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    KEY idx_inventory_allocations_order (order_id),
    KEY idx_inventory_allocations_status (status),
    KEY idx_inventory_allocations_request_id (request_id),
    CONSTRAINT fk_inventory_allocations_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_inventory_allocations_reverses FOREIGN KEY (reverses_allocation_id) REFERENCES inventory_allocations (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 领用明细：批次、订单明细、数量、接入节点，以及对应的库存流水行与履约台账记录
CREATE TABLE inventory_allocation_lines (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    allocation_id BIGINT UNSIGNED NOT NULL,
    batch_id BIGINT UNSIGNED NOT NULL,
    order_item_id BIGINT UNSIGNED NOT NULL,
    quantity INT UNSIGNED NOT NULL,
    target_node VARCHAR(32) NOT NULL,
    movement_line_id BIGINT UNSIGNED NOT NULL,
    fulfillment_entry_id BIGINT UNSIGNED NOT NULL,
    reverses_line_id BIGINT UNSIGNED NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    KEY idx_inventory_allocation_lines_allocation (allocation_id),
    KEY idx_inventory_allocation_lines_batch (batch_id),
    KEY idx_inventory_allocation_lines_order_item (order_item_id),
    KEY idx_inventory_allocation_lines_movement_line (movement_line_id),
    KEY idx_inventory_allocation_lines_fulfillment (fulfillment_entry_id),
    KEY idx_inventory_allocation_lines_request_id (request_id),
    CONSTRAINT fk_inventory_allocation_lines_allocation FOREIGN KEY (allocation_id) REFERENCES inventory_allocations (id),
    CONSTRAINT fk_inventory_allocation_lines_batch FOREIGN KEY (batch_id) REFERENCES inventory_batches (id),
    CONSTRAINT fk_inventory_allocation_lines_order_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT fk_inventory_allocation_lines_movement_line FOREIGN KEY (movement_line_id) REFERENCES inventory_movement_lines (id),
    CONSTRAINT fk_inventory_allocation_lines_fulfillment FOREIGN KEY (fulfillment_entry_id) REFERENCES fulfillment_entries (id),
    CONSTRAINT fk_inventory_allocation_lines_reverses FOREIGN KEY (reverses_line_id) REFERENCES inventory_allocation_lines (id),
    CONSTRAINT ck_inventory_allocation_lines_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ==========================================================================================
-- 原 V9__order_inventory_plan.sql
-- ==========================================================================================

-- 草稿库存计划（任务 4.8）：草稿订单「打算给哪条明细领用哪个批次、多少件」的参考计划。
-- 计划不占用库存（specs/inventory-management/spec.md「草稿库存计划不占用库存」）：本表不产生库存流水、
-- 不改 inventory_batches.quantity；确认时按批次聚合重验余额，不足须管理员明确转生产。
-- 实际领用仍由 4.6 的显式领用命令（inventory_allocations）产生，确认不创建领用事实。
-- 表归属 orders（database-design.md §7）；因 FK 依赖 inventory_batches，随 V8 之后的 V9 建立。
CREATE TABLE order_inventory_plan_lines (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id BIGINT UNSIGNED NOT NULL,
    order_item_id BIGINT UNSIGNED NOT NULL,
    batch_id BIGINT UNSIGNED NOT NULL,
    quantity INT UNSIGNED NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    -- 同一明细 + 同一批次只保留一条计划行（要更多数量就改这一行）；同批次仍可拆给不同明细
    UNIQUE KEY uk_order_inventory_plan_lines_target (order_item_id, batch_id),
    KEY idx_order_inventory_plan_lines_order (order_id),
    KEY idx_order_inventory_plan_lines_batch (batch_id),
    KEY idx_order_inventory_plan_lines_request_id (request_id),
    CONSTRAINT fk_order_inventory_plan_lines_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_order_inventory_plan_lines_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT fk_order_inventory_plan_lines_batch FOREIGN KEY (batch_id) REFERENCES inventory_batches (id),
    CONSTRAINT ck_order_inventory_plan_lines_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ==========================================================================================
-- 原 V10__production.sql
-- ==========================================================================================

-- 生产模块（阶段五）：生产任务头/明细、逐明细核验、显式返工来源、报废与数量回转、超额预占、
-- 工作台提醒、其他排班与工时更正。任务头不参与数量流转，任务明细是最小事实边界。
-- 表归属见 database-design.md §8，数量口径见 domain-and-quantity-model.md §5–§7/§11，需求见 specs/production-management/spec.md，
-- 施工文档见 docs/architecture/production-module-design.md §3。
-- 事实不可变：核验、来源、预占、工时核验与更正只追加；计划取消只改状态并恢复来源余额，不物理删除。
-- 本迁移只建表与约束，不写入任何数据。

-- 生产任务头（阶段五）：一次排班操作的组织容器，**不参与任何数量流转**；
-- 不保存 quantity / completed_quantity / 来源余额等可用于履约、来源或产能计算的汇总列。
CREATE TABLE production_tasks (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_no CHAR(8) NOT NULL,
    task_date DATE NOT NULL,
    employee_id BIGINT UNSIGNED NOT NULL,
    employee_name_snapshot VARCHAR(100) NOT NULL,
    work_type_id BIGINT UNSIGNED NOT NULL,
    work_type_name_snapshot VARCHAR(50) NOT NULL,
    -- NORMAL / REWORK；超额预占与阶段八售后生产使用独立事实边界，不进入本列
    task_type VARCHAR(16) NOT NULL,
    note VARCHAR(500) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_production_tasks_task_no (task_no),
    KEY idx_production_tasks_date_employee (task_date, employee_id),
    KEY idx_production_tasks_type (task_type),
    KEY idx_production_tasks_request_id (request_id),
    CONSTRAINT fk_production_tasks_employee FOREIGN KEY (employee_id) REFERENCES employees (id),
    CONSTRAINT fk_production_tasks_work_type FOREIGN KEY (work_type_id) REFERENCES work_types (id),
    CONSTRAINT ck_production_tasks_type CHECK (task_type IN ('NORMAL', 'REWORK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 生产任务明细：最小事实边界，承载订单/产品/工序/来源/计划数量与创建时能力、时间快照
CREATE TABLE production_task_items (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_id BIGINT UNSIGNED NOT NULL,
    item_no INT UNSIGNED NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    order_item_id BIGINT UNSIGNED NOT NULL,
    product_id BIGINT UNSIGNED NOT NULL,
    product_no CHAR(6) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    node VARCHAR(32) NOT NULL,
    planned_quantity INT UNSIGNED NOT NULL,
    -- ORDER（正常需求）/ REWORK_SOURCE（显式返工来源）/ QUANTITY_RETURN（同工序报废回转）/ AFTER_SALES_SOURCE（阶段八）
    source_type VARCHAR(32) NOT NULL,
    -- 来源明细行；ORDER 时为 order_item_id 本身，保证唯一键对正常来源同样生效
    source_id BIGINT UNSIGNED NOT NULL,
    -- 创建时冻结：单件标准分钟与估算分钟（REWORK 不计正常工时，估算分钟为 0）
    standard_minutes INT UNSIGNED NOT NULL,
    estimated_minutes BIGINT UNSIGNED NOT NULL,
    making_effective_hour_rate DECIMAL(9,6) NOT NULL,
    workday_hours DECIMAL(19,4) NOT NULL,
    mold_quantity INT UNSIGNED NOT NULL,
    daily_batch_limit INT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
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
    UNIQUE KEY uk_production_task_items_item_no (task_id, item_no),
    -- 同一任务内同一订单明细、同一工序、同一来源不得重复占用
    UNIQUE KEY uk_production_task_items_source (task_id, order_item_id, node, source_type, source_id),
    KEY idx_production_task_items_item_node (order_item_id, node),
    KEY idx_production_task_items_product_node (product_id, node),
    KEY idx_production_task_items_source_ref (source_type, source_id),
    KEY idx_production_task_items_status (status),
    KEY idx_production_task_items_request_id (request_id),
    CONSTRAINT fk_production_task_items_task FOREIGN KEY (task_id) REFERENCES production_tasks (id),
    CONSTRAINT fk_production_task_items_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_production_task_items_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT fk_production_task_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT ck_production_task_items_quantity CHECK (planned_quantity > 0),
    CONSTRAINT ck_production_task_items_estimated CHECK (estimated_minutes >= 0),
    CONSTRAINT ck_production_task_items_status CHECK (status IN ('PENDING', 'VERIFIED', 'CANCELLED')),
    CONSTRAINT ck_production_task_items_source CHECK (source_type IN ('ORDER', 'REWORK_SOURCE',
        'QUANTITY_RETURN', 'AFTER_SALES_SOURCE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 一次性核验：每条任务明细最多一条有效核验（唯一外键）
CREATE TABLE production_verifications (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_item_id BIGINT UNSIGNED NOT NULL,
    task_id BIGINT UNSIGNED NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    order_item_id BIGINT UNSIGNED NOT NULL,
    product_id BIGINT UNSIGNED NOT NULL,
    node VARCHAR(32) NOT NULL,
    planned_quantity INT UNSIGNED NOT NULL,
    completed_quantity INT UNSIGNED NOT NULL,
    qualified_quantity INT UNSIGNED NOT NULL,
    rework_quantity INT UNSIGNED NOT NULL,
    scrap_quantity INT UNSIGNED NOT NULL,
    -- 未完成 = 计划数量 − 本次完成（由应用在同一事务内计算并落事实）
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
    UNIQUE KEY uk_production_verifications_item (task_item_id),
    KEY idx_production_verifications_item_node (order_item_id, node),
    KEY idx_production_verifications_request_id (request_id),
    CONSTRAINT fk_production_verifications_task_item FOREIGN KEY (task_item_id) REFERENCES production_task_items (id),
    CONSTRAINT fk_production_verifications_task FOREIGN KEY (task_id) REFERENCES production_tasks (id),
    CONSTRAINT fk_production_verifications_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_production_verifications_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT fk_production_verifications_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT ck_production_verifications_equation CHECK (
        completed_quantity = qualified_quantity + rework_quantity + scrap_quantity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 返工来源：管理员基于返工事实显式创建；可拆多个 REWORK 明细，支持多轮父子链。
-- 余额 = total_quantity − arranged_quantity；阶段五不采用任何基于工序顺序的返工目标限制。
CREATE TABLE rework_sources (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    source_no CHAR(8) NOT NULL,
    origin_verification_id BIGINT UNSIGNED NOT NULL,
    origin_task_item_id BIGINT UNSIGNED NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    order_item_id BIGINT UNSIGNED NOT NULL,
    product_id BIGINT UNSIGNED NOT NULL,
    node VARCHAR(32) NOT NULL,
    total_quantity INT UNSIGNED NOT NULL,
    arranged_quantity INT UNSIGNED NOT NULL DEFAULT 0,
    round_no INT UNSIGNED NOT NULL,
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
    UNIQUE KEY uk_rework_sources_source_no (source_no),
    KEY idx_rework_sources_item (order_item_id, node),
    KEY idx_rework_sources_verification (origin_verification_id),
    KEY idx_rework_sources_previous (previous_source_id),
    KEY idx_rework_sources_request_id (request_id),
    CONSTRAINT fk_rework_sources_verification FOREIGN KEY (origin_verification_id)
        REFERENCES production_verifications (id),
    CONSTRAINT fk_rework_sources_task_item FOREIGN KEY (origin_task_item_id)
        REFERENCES production_task_items (id),
    CONSTRAINT fk_rework_sources_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_rework_sources_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT fk_rework_sources_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_rework_sources_previous FOREIGN KEY (previous_source_id) REFERENCES rework_sources (id),
    CONSTRAINT ck_rework_sources_quantity CHECK (total_quantity > 0 AND arranged_quantity <= total_quantity),
    CONSTRAINT ck_rework_sources_round CHECK (round_no > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 报废事实（不可变）：每条核验最多一条报废事实，不产生替代类型、不增加订单需求、不产生上游或库存合格
CREATE TABLE scrap_records (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    verification_id BIGINT UNSIGNED NOT NULL,
    task_item_id BIGINT UNSIGNED NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    order_item_id BIGINT UNSIGNED NOT NULL,
    product_id BIGINT UNSIGNED NOT NULL,
    node VARCHAR(32) NOT NULL,
    scrap_quantity INT UNSIGNED NOT NULL,
    reason VARCHAR(500) NULL,
    operator_username VARCHAR(100) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scrap_records_verification (verification_id),
    KEY idx_scrap_records_item (order_item_id, node),
    KEY idx_scrap_records_request_id (request_id),
    CONSTRAINT fk_scrap_records_verification FOREIGN KEY (verification_id) REFERENCES production_verifications (id),
    CONSTRAINT fk_scrap_records_task_item FOREIGN KEY (task_item_id) REFERENCES production_task_items (id),
    CONSTRAINT fk_scrap_records_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_scrap_records_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT fk_scrap_records_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT ck_scrap_records_quantity CHECK (scrap_quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 数量回转（不可变）：报废数量回到**发生报废工序**的普通可安排/可执行额度，后续只能创建 NORMAL 明细
CREATE TABLE production_quantity_returns (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scrap_record_id BIGINT UNSIGNED NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    order_item_id BIGINT UNSIGNED NOT NULL,
    product_id BIGINT UNSIGNED NOT NULL,
    node VARCHAR(32) NOT NULL,
    returned_quantity INT UNSIGNED NOT NULL,
    allocated_quantity INT UNSIGNED NOT NULL DEFAULT 0,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_production_quantity_returns_scrap (scrap_record_id),
    KEY idx_production_quantity_returns_item (order_item_id, node),
    KEY idx_production_quantity_returns_request_id (request_id),
    CONSTRAINT fk_production_quantity_returns_scrap FOREIGN KEY (scrap_record_id) REFERENCES scrap_records (id),
    CONSTRAINT fk_production_quantity_returns_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_production_quantity_returns_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT fk_production_quantity_returns_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT ck_production_quantity_returns_quantity CHECK (returned_quantity > 0
        AND allocated_quantity <= returned_quantity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 超额任务（独立预占边界）：只在执行当天创建，来源只能是未来日期的 NORMAL 任务明细
CREATE TABLE overtime_tasks (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_no CHAR(8) NOT NULL,
    task_date DATE NOT NULL,
    employee_id BIGINT UNSIGNED NOT NULL,
    employee_name_snapshot VARCHAR(100) NOT NULL,
    work_type_id BIGINT UNSIGNED NOT NULL,
    work_type_name_snapshot VARCHAR(50) NOT NULL,
    note VARCHAR(500) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_overtime_tasks_task_no (task_no),
    KEY idx_overtime_tasks_date (task_date),
    KEY idx_overtime_tasks_employee (employee_id),
    KEY idx_overtime_tasks_request_id (request_id),
    CONSTRAINT fk_overtime_tasks_employee FOREIGN KEY (employee_id) REFERENCES employees (id),
    CONSTRAINT fk_overtime_tasks_work_type FOREIGN KEY (work_type_id) REFERENCES work_types (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 超额任务明细：自身承载一次性核验事实（PENDING → VERIFIED），不进入普通核验表与正常产能
CREATE TABLE overtime_task_items (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_id BIGINT UNSIGNED NOT NULL,
    item_no INT UNSIGNED NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    order_item_id BIGINT UNSIGNED NOT NULL,
    product_id BIGINT UNSIGNED NOT NULL,
    product_no CHAR(6) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    node VARCHAR(32) NOT NULL,
    planned_quantity INT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    completed_quantity INT UNSIGNED NULL,
    qualified_quantity INT UNSIGNED NULL,
    rework_quantity INT UNSIGNED NULL,
    scrap_quantity INT UNSIGNED NULL,
    incomplete_quantity INT UNSIGNED NULL,
    verify_note VARCHAR(500) NULL,
    verified_by VARCHAR(100) NULL,
    verified_at DATETIME(6) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_overtime_task_items_item_no (task_id, item_no),
    KEY idx_overtime_task_items_item (order_item_id, node),
    KEY idx_overtime_task_items_status (status),
    KEY idx_overtime_task_items_request_id (request_id),
    CONSTRAINT fk_overtime_task_items_task FOREIGN KEY (task_id) REFERENCES overtime_tasks (id),
    CONSTRAINT fk_overtime_task_items_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_overtime_task_items_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT fk_overtime_task_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT ck_overtime_task_items_quantity CHECK (planned_quantity > 0),
    CONSTRAINT ck_overtime_task_items_status CHECK (status IN ('PENDING', 'VERIFIED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 超额预占：超额明细从未来 NORMAL 任务明细预占数量；不修改未来明细原计划数量
CREATE TABLE overtime_preemptions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    overtime_task_item_id BIGINT UNSIGNED NOT NULL,
    future_task_item_id BIGINT UNSIGNED NOT NULL,
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
    -- 同一超额明细对同一未来明细只有一条预占
    UNIQUE KEY uk_overtime_preemptions_pair (overtime_task_item_id, future_task_item_id),
    -- 有效预占合计的加锁索引
    KEY idx_overtime_preemptions_future (future_task_item_id, status),
    KEY idx_overtime_preemptions_item (order_item_id),
    KEY idx_overtime_preemptions_request_id (request_id),
    CONSTRAINT fk_overtime_preemptions_overtime FOREIGN KEY (overtime_task_item_id)
        REFERENCES overtime_task_items (id),
    CONSTRAINT fk_overtime_preemptions_future FOREIGN KEY (future_task_item_id)
        REFERENCES production_task_items (id),
    CONSTRAINT fk_overtime_preemptions_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_overtime_preemptions_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT ck_overtime_preemptions_quantity CHECK (preempted_quantity > 0),
    CONSTRAINT ck_overtime_preemptions_status CHECK (status IN ('ACTIVE', 'RELEASED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 工作台提醒：只辅助工作台，不是数量事实来源（数量以任务明细/核验/来源/预占为准，提醒可重建）
CREATE TABLE production_reminders (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    -- INCOMPLETE / OVERTIME_PENDING_VERIFY / PLAN_ADJUSTMENT
    reminder_type VARCHAR(32) NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    order_item_id BIGINT UNSIGNED NOT NULL,
    node VARCHAR(32) NOT NULL,
    task_item_id BIGINT UNSIGNED NULL,
    verification_id BIGINT UNSIGNED NULL,
    preemption_id BIGINT UNSIGNED NULL,
    -- 计划待调整时指向受影响的未来 NORMAL 任务明细
    future_task_item_id BIGINT UNSIGNED NULL,
    quantity INT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    -- RESCHEDULED / PARTIAL / DEFERRED / ADJUSTED / NO_ADJUSTMENT / SUPERSEDED
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
    KEY idx_production_reminders_task_item (task_item_id),
    KEY idx_production_reminders_future_task_item (future_task_item_id, status),
    KEY idx_production_reminders_request_id (request_id),
    CONSTRAINT fk_production_reminders_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_production_reminders_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT fk_production_reminders_task_item FOREIGN KEY (task_item_id) REFERENCES production_task_items (id),
    CONSTRAINT fk_production_reminders_verification FOREIGN KEY (verification_id)
        REFERENCES production_verifications (id),
    CONSTRAINT fk_production_reminders_preemption FOREIGN KEY (preemption_id) REFERENCES overtime_preemptions (id),
    CONSTRAINT fk_production_reminders_future_task_item FOREIGN KEY (future_task_item_id)
        REFERENCES production_task_items (id),
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

-- ==========================================================================================
-- 原 V11__shipments.sql
-- ==========================================================================================

-- 发货模块（阶段六）：发货批次、明细、来源追溯、物流修改历史、等量更正关系。
-- 表归属 orders（database-design.md §9），数量口径见 domain-and-quantity-model.md §9，
-- 需求见 specs/order-lifecycle/spec.md，施工文档见 docs/architecture/shipment-module-design.md §3。
-- 草稿不产生任何事实；确认只消耗订单可发货投影，**不再扣减原库存**；已确认事实一律用反向事实或更正关系修正。

-- 发货批次：编号、状态、日期、原始物流快照与当前有效物流值
CREATE TABLE shipments (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    shipment_no CHAR(8) NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    shipment_date DATE NOT NULL,
    -- 原始物流快照：确认时冻结，之后不再变化
    carrier VARCHAR(100) NULL,
    tracking_no VARCHAR(100) NULL,
    freight DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    logistics_note VARCHAR(500) NULL,
    -- 当前有效物流值：只允许通过物流修改命令变更，并保留前后值
    current_carrier VARCHAR(100) NULL,
    current_tracking_no VARCHAR(100) NULL,
    current_freight DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    current_logistics_note VARCHAR(500) NULL,
    note VARCHAR(500) NULL,
    confirmed_at DATETIME(6) NULL,
    confirmed_by VARCHAR(100) NULL,
    voided_at DATETIME(6) NULL,
    voided_by VARCHAR(100) NULL,
    void_reason VARCHAR(500) NULL,
    -- 更正时指向被替代的原批次（原批次为 VOIDED）
    replaces_shipment_id BIGINT UNSIGNED NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_shipments_shipment_no (shipment_no),
    KEY idx_shipments_order (order_id, status),
    KEY idx_shipments_status (status),
    KEY idx_shipments_date (shipment_date),
    KEY idx_shipments_request_id (request_id),
    CONSTRAINT fk_shipments_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_shipments_replaces FOREIGN KEY (replaces_shipment_id) REFERENCES shipments (id),
    CONSTRAINT ck_shipments_status CHECK (status IN ('DRAFT', 'CONFIRMED', 'VOIDED')),
    CONSTRAINT ck_shipments_freight CHECK (freight >= 0 AND current_freight >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 发货明细：本次数量 + 商品与收货展示快照 + 确认时的累计发货/未交付快照
CREATE TABLE shipment_items (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    shipment_id BIGINT UNSIGNED NOT NULL,
    order_item_id BIGINT UNSIGNED NOT NULL,
    line_no INT UNSIGNED NOT NULL,
    quantity INT UNSIGNED NOT NULL,
    product_no VARCHAR(6) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    recipient_name VARCHAR(100) NULL,
    recipient_phone VARCHAR(32) NULL,
    region VARCHAR(100) NULL,
    address VARCHAR(300) NULL,
    -- 确认时快照：本次之后该明细的累计有效发货与未交付需求（草稿阶段为 NULL）
    cumulative_shipped_quantity INT UNSIGNED NULL,
    undelivered_quantity INT UNSIGNED NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_shipment_items_shipment_item (shipment_id, order_item_id),
    KEY idx_shipment_items_order_item (order_item_id),
    KEY idx_shipment_items_request_id (request_id),
    CONSTRAINT fk_shipment_items_shipment FOREIGN KEY (shipment_id) REFERENCES shipments (id),
    CONSTRAINT fk_shipment_items_order_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT ck_shipment_items_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 发货来源追溯：只追溯来源，不再次改变库存（库存已在领用时扣减）
CREATE TABLE shipment_source_links (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    shipment_item_id BIGINT UNSIGNED NOT NULL,
    -- INVENTORY_ALLOCATION / PRODUCTION_QUALIFIED / FINISHED_SURPLUS
    source_type VARCHAR(32) NOT NULL,
    source_id BIGINT UNSIGNED NOT NULL,
    source_line_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    quantity INT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    -- 同一发货明细对同一来源记录只追溯一次
    UNIQUE KEY uk_shipment_source_links_source (shipment_item_id, source_type, source_id, source_line_id),
    KEY idx_shipment_source_links_source (source_type, source_id),
    KEY idx_shipment_source_links_request_id (request_id),
    CONSTRAINT fk_shipment_source_links_item FOREIGN KEY (shipment_item_id) REFERENCES shipment_items (id),
    CONSTRAINT ck_shipment_source_links_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 物流修改历史：只允许公司、单号、运费、备注，保留前后值与原因
CREATE TABLE shipment_logistics_changes (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    shipment_id BIGINT UNSIGNED NOT NULL,
    before_carrier VARCHAR(100) NULL,
    after_carrier VARCHAR(100) NULL,
    before_tracking_no VARCHAR(100) NULL,
    after_tracking_no VARCHAR(100) NULL,
    before_freight DECIMAL(19,4) NULL,
    after_freight DECIMAL(19,4) NULL,
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
    KEY idx_shipment_logistics_changes_shipment (shipment_id, id),
    KEY idx_shipment_logistics_changes_request_id (request_id),
    CONSTRAINT fk_shipment_logistics_changes_shipment FOREIGN KEY (shipment_id) REFERENCES shipments (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 等量更正关系：一个原批次最多一次等量更正
CREATE TABLE shipment_corrections (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    original_shipment_id BIGINT UNSIGNED NOT NULL,
    replacement_shipment_id BIGINT UNSIGNED NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_shipment_corrections_original (original_shipment_id),
    KEY idx_shipment_corrections_replacement (replacement_shipment_id),
    KEY idx_shipment_corrections_request_id (request_id),
    CONSTRAINT fk_shipment_corrections_original FOREIGN KEY (original_shipment_id) REFERENCES shipments (id),
    CONSTRAINT fk_shipment_corrections_replacement FOREIGN KEY (replacement_shipment_id) REFERENCES shipments (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ==========================================================================================
-- 原 V12__settlement.sql
-- ==========================================================================================

-- 收退款与关闭（阶段七）：不可变收退款事实 + 订单款项投影。
-- 表归属 orders（database-design.md §10），结清口径见 docs/architecture/settlement-module-design.md §4、
-- design.md §7.1，需求见 specs/order-lifecycle/spec.md。
-- 收款与退款只追加，不提供编辑/删除；退款必须关联来源（变更单或售后单），累计退款不得超过累计收款。
-- 售后退款只进入累计实际净收，不冲减订单结清净额、不产生新的原订单待收/待退。

-- 收款事实（不可变）
CREATE TABLE payments (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    payment_no CHAR(8) NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    business_date DATE NOT NULL,
    method VARCHAR(50) NOT NULL,
    note VARCHAR(500) NULL,
    operator_username VARCHAR(100) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payments_payment_no (payment_no),
    KEY idx_payments_order (order_id, business_date),
    KEY idx_payments_request_id (request_id),
    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_payments_amount CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 退款事实（不可变）：必须关联来源（订单变更单或售后单）
CREATE TABLE refunds (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    refund_no CHAR(8) NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    business_date DATE NOT NULL,
    method VARCHAR(50) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    note VARCHAR(500) NULL,
    -- ORDER_CHANGE（订单变更退款，参与结清）/ AFTER_SALES（售后退款，只进累计实际净收）
    source_type VARCHAR(32) NOT NULL,
    source_id BIGINT UNSIGNED NOT NULL,
    operator_username VARCHAR(100) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refunds_refund_no (refund_no),
    KEY idx_refunds_order (order_id, business_date),
    KEY idx_refunds_source (source_type, source_id),
    KEY idx_refunds_request_id (request_id),
    CONSTRAINT fk_refunds_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_refunds_amount CHECK (amount > 0),
    CONSTRAINT ck_refunds_source_type CHECK (source_type IN ('ORDER_CHANGE', 'AFTER_SALES'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 订单款项投影：由领域服务在写收退款事实的同一事务内更新，可从事实按来源类型重建
CREATE TABLE order_settlement_balances (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id BIGINT UNSIGNED NOT NULL,
    paid_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    change_refund_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    after_sales_refund_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    -- 结清净额 = 累计订单收款 − 累计订单变更退款
    net_settled_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    -- 当前有效应收（随订单变更更新）
    effective_receivable_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    -- 订单待退款 = max(累计收款 − 当前有效应收 − 累计变更退款, 0)
    refund_pending_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_settlement_balances_order (order_id),
    KEY idx_order_settlement_balances_request_id (request_id),
    CONSTRAINT fk_order_settlement_balances_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_order_settlement_balances_non_negative CHECK (
        paid_amount >= 0 AND change_refund_amount >= 0 AND after_sales_refund_amount >= 0
        AND net_settled_amount >= 0 AND effective_receivable_amount >= 0 AND refund_pending_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ==========================================================================================
-- 原 V13__after_sales.sql
-- ==========================================================================================

-- 售后（阶段八）：售后单、售后明细、退回核验、售后补发台账、补发发货关联、售后更正记录。
-- 表归属 orders（database-design.md §11），口径见 docs/architecture/after-sales-module-design.md §3/§4、
-- domain-and-quantity-model.md §12，需求见 specs/order-lifecycle/spec.md。
-- 售后独立于原订单履约：不回写原订单的订购数量、累计发货、未交付需求、应收或主状态；
-- 退回不自动入库、不恢复原发货库存；补发来源只增加可补发，确认补发才增加已补发。

-- 售后单
CREATE TABLE after_sales_cases (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    case_no CHAR(8) NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    -- REWORK / REPLACEMENT / REWORK_AND_REPLACEMENT
    case_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    problem VARCHAR(500) NOT NULL,
    solution VARCHAR(500) NULL,
    note VARCHAR(500) NULL,
    closed_at DATETIME(6) NULL,
    closed_by VARCHAR(100) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_after_sales_cases_case_no (case_no),
    KEY idx_after_sales_cases_order (order_id, status),
    KEY idx_after_sales_cases_request_id (request_id),
    CONSTRAINT fk_after_sales_cases_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_after_sales_cases_type CHECK (case_type IN ('REWORK', 'REPLACEMENT', 'REWORK_AND_REPLACEMENT')),
    CONSTRAINT ck_after_sales_cases_status CHECK (status IN ('OPEN', 'COMPLETED', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 售后明细：来源必须是已确认且有效的原发货批次明细
CREATE TABLE after_sales_items (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    case_id BIGINT UNSIGNED NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    order_item_id BIGINT UNSIGNED NOT NULL,
    -- 已确认发货批次的明细；同一发货明细只允许一个有效售后占用（唯一键）
    shipment_item_id BIGINT UNSIGNED NOT NULL,
    product_no VARCHAR(6) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    seam_quantity INT UNSIGNED NOT NULL DEFAULT 0,
    accepted_quantity INT UNSIGNED NOT NULL,
    returned_quantity INT UNSIGNED NOT NULL DEFAULT 0,
    replacement_required_quantity INT UNSIGNED NOT NULL DEFAULT 0,
    return_verification_id BIGINT UNSIGNED NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_after_sales_items_shipment_item (shipment_item_id),
    KEY idx_after_sales_items_case (case_id),
    KEY idx_after_sales_items_order_item (order_item_id),
    KEY idx_after_sales_items_request_id (request_id),
    CONSTRAINT fk_after_sales_items_case FOREIGN KEY (case_id) REFERENCES after_sales_cases (id),
    CONSTRAINT fk_after_sales_items_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_after_sales_items_order_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT fk_after_sales_items_shipment_item FOREIGN KEY (shipment_item_id) REFERENCES shipment_items (id),
    CONSTRAINT ck_after_sales_items_accepted CHECK (accepted_quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 退回核验：每售后明细一条，退回 = 返工 + 报废（数据库级等式）
CREATE TABLE after_sales_return_verifications (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    after_sales_item_id BIGINT UNSIGNED NOT NULL,
    returned_quantity INT UNSIGNED NOT NULL,
    rework_quantity INT UNSIGNED NOT NULL,
    scrap_quantity INT UNSIGNED NOT NULL,
    reason VARCHAR(500) NULL,
    verified_by VARCHAR(100) NOT NULL,
    verified_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_after_sales_return_verifications_item (after_sales_item_id),
    KEY idx_after_sales_return_verifications_request_id (request_id),
    CONSTRAINT fk_after_sales_return_verifications_item FOREIGN KEY (after_sales_item_id)
        REFERENCES after_sales_items (id),
    CONSTRAINT ck_after_sales_return_equation CHECK (returned_quantity = rework_quantity + scrap_quantity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 售后补发台账（不可变）：库存接入 / 售后返工合格 / 售后生产合格 / 补发发货消耗 / 冲销
CREATE TABLE after_sales_fulfillment_entries (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    after_sales_item_id BIGINT UNSIGNED NOT NULL,
    -- INVENTORY_INFLOW / REWORK_QUALIFIED / PRODUCTION_QUALIFIED / REPLACEMENT_CONSUME / REVERSAL
    entry_type VARCHAR(32) NOT NULL,
    direction VARCHAR(8) NOT NULL,
    quantity INT UNSIGNED NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    source_line_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    business_date DATE NOT NULL,
    operator_username VARCHAR(100) NULL,
    note VARCHAR(500) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    -- 每笔来源只接入一次
    UNIQUE KEY uk_after_sales_entries_source (source_type, source_id, source_line_id, entry_type, direction),
    KEY idx_after_sales_entries_item (after_sales_item_id, id),
    KEY idx_after_sales_entries_request_id (request_id),
    CONSTRAINT fk_after_sales_entries_item FOREIGN KEY (after_sales_item_id) REFERENCES after_sales_items (id),
    CONSTRAINT ck_after_sales_entries_quantity CHECK (quantity > 0),
    CONSTRAINT ck_after_sales_entries_direction CHECK (direction IN ('IN', 'OUT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 补发发货关联：补发批次确认后才增加已补发
CREATE TABLE after_sales_shipment_links (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    after_sales_item_id BIGINT UNSIGNED NOT NULL,
    shipment_id BIGINT UNSIGNED NOT NULL,
    shipment_item_id BIGINT UNSIGNED NOT NULL,
    quantity INT UNSIGNED NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_after_sales_shipment_links_item (after_sales_item_id, shipment_item_id),
    KEY idx_after_sales_shipment_links_shipment (shipment_id),
    KEY idx_after_sales_shipment_links_request_id (request_id),
    CONSTRAINT fk_after_sales_shipment_links_item FOREIGN KEY (after_sales_item_id) REFERENCES after_sales_items (id),
    CONSTRAINT fk_after_sales_shipment_links_shipment FOREIGN KEY (shipment_id) REFERENCES shipments (id),
    CONSTRAINT fk_after_sales_shipment_links_shipment_item FOREIGN KEY (shipment_item_id)
        REFERENCES shipment_items (id),
    CONSTRAINT ck_after_sales_shipment_links_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 售后更正记录：不覆盖原事实，保留原值与来源链
CREATE TABLE after_sales_corrections (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    case_id BIGINT UNSIGNED NOT NULL,
    -- RETURN_VERIFICATION / ITEM_QUANTITY
    target_type VARCHAR(32) NOT NULL,
    target_id BIGINT UNSIGNED NOT NULL,
    before_value VARCHAR(100) NOT NULL,
    after_value VARCHAR(100) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    operator_username VARCHAR(100) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    KEY idx_after_sales_corrections_case (case_id, id),
    KEY idx_after_sales_corrections_target (target_type, target_id),
    KEY idx_after_sales_corrections_request_id (request_id),
    CONSTRAINT fk_after_sales_corrections_case FOREIGN KEY (case_id) REFERENCES after_sales_cases (id),
    CONSTRAINT ck_after_sales_corrections_type CHECK (target_type IN ('RETURN_VERIFICATION', 'ITEM_QUANTITY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ==========================================================================================
-- 原 V14__after_sales_production.sql
-- ==========================================================================================

-- 售后生产来源（阶段八 8.4/8.5）：售后返工与售后补发生产的来源额度。
-- 口径见 docs/architecture/after-sales-module-design.md §4.3、domain-and-quantity-model.md §12，
-- 需求见 specs/production-management/spec.md「生产计划必须记录来源」与 specs/order-lifecycle/spec.md
-- 「售后必须独立于原订单履约」。售后来源属于生产模块（生产计划的来源额度），
-- 只引用售后明细（orders 表）作为来源，售后合格只增加可补发，不进入订单工序需求与通用库存。

CREATE TABLE after_sales_production_sources (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    after_sales_item_id BIGINT UNSIGNED NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    order_item_id BIGINT UNSIGNED NOT NULL,
    -- REWORK（退回核验的售后返工）/ REPLACEMENT（补发需求缺口）
    purpose VARCHAR(16) NOT NULL,
    -- 售后计划的目标工序；售后数量不属于订单工序需求，故按来源分别记额度
    node VARCHAR(32) NOT NULL,
    total_quantity INT UNSIGNED NOT NULL,
    arranged_quantity INT UNSIGNED NOT NULL DEFAULT 0,
    reason VARCHAR(500) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    request_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    -- 同一售后明细的同一用途同一目标工序只有一条来源
    UNIQUE KEY uk_after_sales_production_sources_target (after_sales_item_id, purpose, node),
    KEY idx_after_sales_production_sources_item (order_item_id),
    KEY idx_after_sales_production_sources_request_id (request_id),
    CONSTRAINT fk_after_sales_production_sources_after_sales_item
        FOREIGN KEY (after_sales_item_id) REFERENCES after_sales_items (id),
    CONSTRAINT fk_after_sales_production_sources_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_after_sales_production_sources_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT ck_after_sales_production_sources_purpose CHECK (purpose IN ('REWORK', 'REPLACEMENT')),
    CONSTRAINT ck_after_sales_production_sources_quantity
        CHECK (total_quantity > 0 AND arranged_quantity <= total_quantity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
