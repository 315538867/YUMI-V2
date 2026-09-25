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
