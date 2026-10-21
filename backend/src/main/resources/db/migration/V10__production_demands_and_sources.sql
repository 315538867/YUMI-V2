-- 生产需求、履约来源、返工来源与售后退回制品池（基线 11 §9.1）

CREATE TABLE production_demands (
    id BIGINT NOT NULL AUTO_INCREMENT,
    demand_business_id VARCHAR(64) NOT NULL,
    demand_type VARCHAR(32) NOT NULL,
    source_module VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_business_id VARCHAR(64) NOT NULL,
    order_business_id VARCHAR(64) NULL,
    order_item_business_id VARCHAR(64) NULL,
    product_business_id VARCHAR(64) NOT NULL,
    product_snapshot_ref VARCHAR(64) NOT NULL,
    scheme_business_id VARCHAR(64) NOT NULL,
    scheme_snapshot_ref VARCHAR(64) NOT NULL,
    target_process VARCHAR(32) NOT NULL,
    basis_returned_source_business_id VARCHAR(64) NULL,
    production_basis_json JSON NOT NULL,
    production_basis_fingerprint CHAR(64) NOT NULL,
    required_quantity INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    content_version BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_demand_business_id (demand_business_id),
    UNIQUE KEY uk_demand_source (source_module, source_type, source_business_id),
    UNIQUE KEY uk_demand_order_item (order_item_business_id),
    KEY ix_demand_product_status (product_business_id, status),
    CONSTRAINT ck_demand_type CHECK (demand_type IN ('NEW_PRODUCT', 'RETURNED_PRODUCT_REPAIR')),
    CONSTRAINT ck_demand_quantity CHECK (required_quantity > 0),
    CONSTRAINT ck_demand_target CHECK (target_process IN ('MAKING', 'BAGGING', 'SEAM_CUTTING'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_sources (
    id BIGINT NOT NULL AUTO_INCREMENT,
    production_source_business_id VARCHAR(64) NOT NULL,
    demand_business_id VARCHAR(64) NOT NULL,
    source_kind VARCHAR(32) NOT NULL,
    physical_root_source_business_id VARCHAR(64) NOT NULL,
    returned_source_business_id VARCHAR(64) NULL,
    product_business_id VARCHAR(64) NOT NULL,
    product_snapshot_ref VARCHAR(64) NOT NULL,
    scheme_business_id VARCHAR(64) NOT NULL,
    scheme_snapshot_ref VARCHAR(64) NOT NULL,
    production_basis_fingerprint CHAR(64) NOT NULL,
    initial_quantity INT NOT NULL,
    target_process VARCHAR(32) NOT NULL,
    opening_status VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    row_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_production_source_business_id (production_source_business_id),
    KEY ix_production_source_demand (demand_business_id),
    CONSTRAINT ck_source_kind CHECK (source_kind IN ('NEW_CREATION', 'RETURNED_EXISTING_PRODUCT')),
    CONSTRAINT ck_source_quantity CHECK (initial_quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_demand_change_facts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    fact_business_id VARCHAR(64) NOT NULL,
    demand_business_id VARCHAR(64) NOT NULL,
    production_source_business_id VARCHAR(64) NOT NULL,
    upstream_change_business_id VARCHAR(64) NULL,
    change_type VARCHAR(32) NOT NULL,
    quantity_before INT NOT NULL,
    quantity_after INT NOT NULL,
    quantity_delta INT NOT NULL,
    cancel_unproduced_quantity INT NOT NULL,
    transfer_produced_quantity INT NOT NULL,
    release_to_return_pool_quantity INT NOT NULL,
    release_repair_gap_quantity INT NOT NULL,
    terminal_exit_allocations_json JSON NULL,
    reason_code VARCHAR(32) NULL,
    reason_text VARCHAR(500) NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    command_execution_id VARCHAR(64) NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_demand_change_fact (fact_business_id),
    UNIQUE KEY uk_demand_change_source (upstream_change_business_id, production_source_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_returned_product_sources (
    id BIGINT NOT NULL AUTO_INCREMENT,
    returned_source_business_id VARCHAR(64) NOT NULL,
    after_sales_return_receipt_item_business_id VARCHAR(64) NOT NULL,
    physical_root_source_business_id VARCHAR(64) NOT NULL,
    prior_delivery_source_type VARCHAR(32) NOT NULL,
    prior_production_source_business_id VARCHAR(64) NULL,
    prior_returned_source_business_id VARCHAR(64) NULL,
    prior_consumption_fact_business_id VARCHAR(64) NULL,
    product_snapshot_ref VARCHAR(64) NOT NULL,
    scheme_snapshot_ref VARCHAR(64) NOT NULL,
    returned_stage_code VARCHAR(32) NOT NULL,
    original_quantity INT NOT NULL,
    available_quantity INT NOT NULL,
    routed_repair_quantity INT NOT NULL,
    disposed_quantity INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    row_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_returned_source_business_id (returned_source_business_id),
    UNIQUE KEY uk_returned_receipt_item (after_sales_return_receipt_item_business_id),
    CONSTRAINT ck_returned_delivery_source CHECK (
        prior_delivery_source_type IN ('PRODUCTION_SOURCE', 'RETURNED_PRODUCT_POOL')),
    CONSTRAINT ck_returned_conservation CHECK (
        original_quantity = available_quantity + routed_repair_quantity + disposed_quantity)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_repair_sources (
    id BIGINT NOT NULL AUTO_INCREMENT,
    repair_source_business_id VARCHAR(64) NOT NULL,
    origin_type VARCHAR(32) NOT NULL,
    origin_module VARCHAR(32) NOT NULL,
    origin_type_code VARCHAR(32) NOT NULL,
    origin_business_id VARCHAR(64) NOT NULL,
    demand_business_id VARCHAR(64) NOT NULL,
    production_source_business_id VARCHAR(64) NOT NULL,
    physical_root_source_business_id VARCHAR(64) NOT NULL,
    original_defect_fact_business_id VARCHAR(64) NULL,
    after_sales_return_receipt_item_business_id VARCHAR(64) NULL,
    returned_source_business_id VARCHAR(64) NULL,
    repair_process_code VARCHAR(32) NOT NULL,
    original_quantity INT NOT NULL,
    available_quantity INT NOT NULL,
    reserved_quantity INT NOT NULL,
    completed_quantity INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    row_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_repair_source_business_id (repair_source_business_id),
    KEY ix_repair_source_origin (origin_type, origin_business_id),
    CONSTRAINT ck_repair_origin CHECK (origin_type IN ('INTERNAL_DEFECT', 'AFTER_SALES_RETURN')),
    CONSTRAINT ck_repair_conservation CHECK (
        available_quantity >= 0 AND reserved_quantity >= 0 AND completed_quantity >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_repair_source_facts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    fact_business_id VARCHAR(64) NOT NULL,
    repair_source_business_id VARCHAR(64) NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    quantity INT NOT NULL,
    task_business_id VARCHAR(64) NULL,
    task_item_business_id VARCHAR(64) NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    corrects_fact_business_id VARCHAR(64) NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_repair_source_fact (fact_business_id),
    KEY ix_repair_source_fact_source (repair_source_business_id, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_change_guards (
    source_module VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_business_id VARCHAR(64) NOT NULL,
    source_version BIGINT NOT NULL,
    demand_business_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (source_module, source_type, source_business_id, source_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_demand_quantity_projections (
    demand_business_id VARCHAR(64) NOT NULL,
    required_quantity INT NOT NULL,
    pending_work_quantity INT NOT NULL,
    in_process_quantity INT NOT NULL,
    target_ready_quantity INT NOT NULL,
    fulfilled_quantity INT NOT NULL,
    fulfillment_gap_quantity INT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (demand_business_id),
    CONSTRAINT ck_demand_projection_nonnegative CHECK (
        pending_work_quantity >= 0 AND in_process_quantity >= 0 AND target_ready_quantity >= 0
        AND fulfilled_quantity >= 0 AND fulfillment_gap_quantity >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_physical_quantity_projections (
    physical_root_source_business_id VARCHAR(64) NOT NULL,
    cumulative_making_created_quantity INT NOT NULL,
    in_system_quantity INT NOT NULL,
    consumed_quantity INT NOT NULL,
    destroyed_quantity INT NOT NULL,
    offline_inventory_quantity INT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (physical_root_source_business_id),
    CONSTRAINT ck_physical_conservation CHECK (
        cumulative_making_created_quantity = in_system_quantity + consumed_quantity
            + destroyed_quantity + offline_inventory_quantity)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_stage_quantity_projections (
    production_source_business_id VARCHAR(64) NOT NULL,
    physical_root_source_business_id VARCHAR(64) NOT NULL,
    stage_code VARCHAR(32) NOT NULL,
    quality_code VARCHAR(32) NOT NULL,
    disposition_code VARCHAR(32) NOT NULL DEFAULT '',
    physical_destination VARCHAR(32) NOT NULL,
    available_quantity INT NOT NULL,
    reserved_quantity INT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (production_source_business_id, stage_code, quality_code,
                 physical_destination, disposition_code),
    CONSTRAINT ck_stage_projection_nonnegative CHECK (
        available_quantity >= 0 AND reserved_quantity >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
