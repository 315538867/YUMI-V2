-- 商品录入配置、当前聚合、方案、预算结果与快照（基线 09 §9）

CREATE TABLE product_entry_defaults (
    config_key VARCHAR(32) NOT NULL,
    glue_loss_rate DECIMAL(12,8) NOT NULL,
    glue_unit_price DECIMAL(19,4) NOT NULL,
    color_paste_unit_price DECIMAL(19,4) NOT NULL,
    row_version BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    PRIMARY KEY (config_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE product_making_star_options (
    star_code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    standard_minutes INT NOT NULL,
    sort_order INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (star_code),
    KEY ix_making_star_status (status, sort_order),
    CONSTRAINT ck_making_star_minutes CHECK (standard_minutes > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE product_process_time_options (
    option_business_id VARCHAR(64) NOT NULL,
    process_code VARCHAR(32) NOT NULL,
    option_code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    standard_minutes INT NOT NULL,
    sort_order INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (option_business_id),
    UNIQUE KEY uk_process_time_option (process_code, option_code),
    KEY ix_process_time_status (process_code, status, sort_order),
    CONSTRAINT ck_process_time_minutes CHECK (standard_minutes > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE product_products (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_business_id VARCHAR(64) NOT NULL,
    product_no VARCHAR(32) NOT NULL,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(500) NULL,
    standard_weight_grams DECIMAL(19,4) NOT NULL,
    making_star_code VARCHAR(32) NOT NULL,
    making_star_name VARCHAR(64) NOT NULL,
    making_standard_minutes INT NOT NULL,
    standard_sale_price DECIMAL(19,2) NOT NULL,
    glue_loss_rate DECIMAL(12,8) NOT NULL,
    glue_unit_price DECIMAL(19,4) NOT NULL,
    color_paste_unit_price DECIMAL(19,4) NOT NULL,
    making_commission DECIMAL(19,4) NOT NULL,
    mould_count INT NOT NULL,
    mould_daily_batches INT NOT NULL,
    daily_capacity INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    content_version BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_business_id (product_business_id),
    UNIQUE KEY uk_product_no (product_no),
    KEY ix_product_status_name (status, name),
    CONSTRAINT ck_product_weight CHECK (standard_weight_grams > 0),
    CONSTRAINT ck_product_mould CHECK (mould_count > 0 AND mould_daily_batches > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE product_making_processes (
    product_business_id VARCHAR(64) NOT NULL,
    other_material_cost DECIMAL(19,4) NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (product_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE product_bagging_processes (
    product_business_id VARCHAR(64) NOT NULL,
    standard_minutes INT NOT NULL,
    labor_cost DECIMAL(19,4) NOT NULL,
    bag_unit_cost DECIMAL(19,4) NOT NULL,
    decoration_unit_price DECIMAL(19,4) NOT NULL,
    other_cost DECIMAL(19,4) NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (product_business_id),
    CONSTRAINT ck_bagging_minutes CHECK (standard_minutes > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE product_seam_cutting_processes (
    product_business_id VARCHAR(64) NOT NULL,
    standard_minutes INT NOT NULL,
    labor_cost DECIMAL(19,4) NOT NULL,
    combined_material_cost DECIMAL(19,4) NOT NULL,
    other_cost DECIMAL(19,4) NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (product_business_id),
    CONSTRAINT ck_seam_minutes CHECK (standard_minutes > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE product_extra_unit_costs (
    product_business_id VARCHAR(64) NOT NULL,
    other_task_labor_cost DECIMAL(19,4) NOT NULL,
    transport_packaging_cost DECIMAL(19,4) NOT NULL,
    daily_misc_cost DECIMAL(19,4) NOT NULL,
    rent_utilities_cost DECIMAL(19,4) NOT NULL,
    mould_unit_amortization_cost DECIMAL(19,4) NOT NULL,
    other_cost DECIMAL(19,4) NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (product_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE product_schemes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    scheme_business_id VARCHAR(64) NOT NULL,
    product_business_id VARCHAR(64) NOT NULL,
    scheme_type VARCHAR(32) NOT NULL,
    name VARCHAR(80) NOT NULL,
    standard_weight_grams DECIMAL(19,4) NOT NULL,
    target_process VARCHAR(32) NOT NULL,
    sale_price DECIMAL(19,2) NOT NULL,
    glue_source VARCHAR(32) NOT NULL,
    color_paste_source VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    source_product_content_version BIGINT NOT NULL,
    content_version BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scheme_business_id (scheme_business_id),
    KEY ix_scheme_product (product_business_id, scheme_type),
    CONSTRAINT ck_scheme_type CHECK (scheme_type IN ('SYSTEM_STANDARD', 'CUSTOM')),
    CONSTRAINT ck_scheme_target CHECK (target_process IN ('MAKING', 'BAGGING', 'SEAM_CUTTING'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE product_scheme_making (
    scheme_business_id VARCHAR(64) NOT NULL,
    standard_minutes INT NOT NULL,
    making_commission DECIMAL(19,4) NOT NULL,
    other_material_cost DECIMAL(19,4) NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (scheme_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE product_scheme_bagging (
    scheme_business_id VARCHAR(64) NOT NULL,
    standard_minutes INT NOT NULL,
    labor_cost DECIMAL(19,4) NOT NULL,
    bag_unit_cost DECIMAL(19,4) NOT NULL,
    bag_source VARCHAR(32) NOT NULL,
    decoration_unit_price DECIMAL(19,4) NOT NULL,
    decoration_source VARCHAR(32) NOT NULL,
    other_cost DECIMAL(19,4) NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (scheme_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE product_scheme_seam_cutting (
    scheme_business_id VARCHAR(64) NOT NULL,
    standard_minutes INT NOT NULL,
    labor_cost DECIMAL(19,4) NOT NULL,
    combined_material_cost DECIMAL(19,4) NOT NULL,
    other_cost DECIMAL(19,4) NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (scheme_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE product_scheme_extra_unit_costs (
    scheme_business_id VARCHAR(64) NOT NULL,
    other_task_labor_cost DECIMAL(19,4) NOT NULL,
    transport_packaging_cost DECIMAL(19,4) NOT NULL,
    daily_misc_cost DECIMAL(19,4) NOT NULL,
    rent_utilities_cost DECIMAL(19,4) NOT NULL,
    mould_unit_amortization_cost DECIMAL(19,4) NOT NULL,
    other_cost DECIMAL(19,4) NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (scheme_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE product_budget_results (
    budget_business_id VARCHAR(64) NOT NULL,
    scheme_business_id VARCHAR(64) NOT NULL,
    product_content_version BIGINT NOT NULL,
    scheme_content_version BIGINT NOT NULL,
    formula_rule_version VARCHAR(32) NOT NULL,
    rounding_version VARCHAR(32) NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    full_resource_unit_cost DECIMAL(19,4) NOT NULL,
    studio_borne_unit_cost DECIMAL(19,4) NOT NULL,
    customer_supplied_unit_value DECIMAL(19,4) NOT NULL,
    expected_unit_revenue DECIMAL(19,2) NOT NULL,
    expected_unit_profit DECIMAL(19,4) NOT NULL,
    expected_profit_rate DECIMAL(12,8) NULL,
    calculated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (budget_business_id),
    UNIQUE KEY uk_budget_scheme (scheme_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE product_budget_result_lines (
    budget_business_id VARCHAR(64) NOT NULL,
    line_no INT NOT NULL,
    process_code VARCHAR(32) NULL,
    component_code VARCHAR(64) NOT NULL,
    cost_nature VARCHAR(32) NOT NULL,
    bearer_type VARCHAR(32) NOT NULL,
    quantity DECIMAL(19,4) NOT NULL,
    unit_price DECIMAL(19,4) NOT NULL,
    full_resource_cost DECIMAL(19,4) NOT NULL,
    studio_borne_cost DECIMAL(19,4) NOT NULL,
    PRIMARY KEY (budget_business_id, line_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE product_snapshots (
    snapshot_business_id VARCHAR(64) NOT NULL,
    product_business_id VARCHAR(64) NOT NULL,
    snapshot_version BIGINT NOT NULL,
    source_content_version BIGINT NOT NULL,
    base_payload_json JSON NOT NULL,
    process_payload_json JSON NOT NULL,
    extra_cost_payload_json JSON NOT NULL,
    daily_capacity INT NOT NULL,
    content_fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (snapshot_business_id),
    UNIQUE KEY uk_product_snapshot_version (product_business_id, source_content_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE product_scheme_snapshots (
    snapshot_business_id VARCHAR(64) NOT NULL,
    scheme_business_id VARCHAR(64) NOT NULL,
    snapshot_version BIGINT NOT NULL,
    source_scheme_content_version BIGINT NOT NULL,
    source_product_content_version BIGINT NOT NULL,
    product_snapshot_business_id VARCHAR(64) NOT NULL,
    complete_scheme_payload_json JSON NOT NULL,
    budget_payload_json JSON NOT NULL,
    content_fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (snapshot_business_id),
    UNIQUE KEY uk_scheme_snapshot_version (
        scheme_business_id, source_scheme_content_version, source_product_content_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
