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
