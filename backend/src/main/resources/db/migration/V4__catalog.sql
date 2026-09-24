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

-- 缝边种类：订单明细行的定制服务；商品只保存默认值指针与缝边价格，不保存缝边数量或缝边成本。
-- 成本单价元/件、允许 0，订单选中时带出作定价提示，收费由订单手填
CREATE TABLE seam_types (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    cost_price DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
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

-- 员工工种：系统预置四道工序，code 由系统固定、名称可改、可停用
CREATE TABLE work_types (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    active TINYINT(1) NOT NULL DEFAULT 1,
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
    std_minutes DECIMAL(9,3) NOT NULL,
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

INSERT INTO catalog_settings (setting_key, setting_value, created_at, updated_at) VALUES
    ('glue_unit_price', 0.000000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('colorpaste_unit_price', 0.000000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('loss_rate_default', 0.000000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('box_labor_default', 0.000000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('transport_packing_default', 0.000000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('sundries_default', 0.000000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('rent_utilities_default', 0.000000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

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
    packaging_std_minutes DECIMAL(9,3) NULL,
    packaging_commission DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    packaging_labor_fee DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    seam_type_id BIGINT UNSIGNED NULL,
    seam_type_name VARCHAR(100) NULL,
    seam_type_cost_price DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
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
