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
