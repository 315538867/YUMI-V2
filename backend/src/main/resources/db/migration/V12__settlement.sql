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
