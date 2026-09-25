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
