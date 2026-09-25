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
