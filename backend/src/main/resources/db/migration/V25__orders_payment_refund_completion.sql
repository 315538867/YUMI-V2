-- 订单支付关联、退款义务投影与完成事实（基线 12 §8.2、§8.3）

CREATE TABLE orders_payment_link_facts (
    fact_business_id VARCHAR(64) NOT NULL,
    order_business_id VARCHAR(64) NOT NULL,
    finance_cash_fact_business_id VARCHAR(64) NOT NULL,
    link_type VARCHAR(32) NOT NULL,
    linked_amount DECIMAL(19,2) NOT NULL,
    reverses_fact_business_id VARCHAR(64) NULL,
    corrects_fact_business_id VARCHAR(64) NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (fact_business_id),
    UNIQUE KEY uk_payment_link (order_business_id, finance_cash_fact_business_id, link_type),
    UNIQUE KEY uk_payment_link_reversal (reverses_fact_business_id),
    KEY ix_payment_link_order (order_business_id, occurred_at),
    CONSTRAINT ck_payment_link_type CHECK (
        link_type IN ('RECEIPT', 'REFUND', 'RECEIPT_REVERSAL', 'REFUND_REVERSAL')),
    CONSTRAINT ck_payment_link_amount CHECK (linked_amount > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE orders_refund_obligations (
    refund_business_id VARCHAR(64) NOT NULL,
    order_business_id VARCHAR(64) NOT NULL,
    decided_amount DECIMAL(19,2) NOT NULL,
    paid_amount DECIMAL(19,2) NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    row_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (refund_business_id),
    UNIQUE KEY uk_refund_obligation_order (order_business_id),
    CONSTRAINT ck_refund_obligation_amount CHECK (
        decided_amount >= 0 AND paid_amount >= 0 AND paid_amount <= decided_amount),
    CONSTRAINT ck_refund_obligation_status CHECK (status IN ('ACTIVE', 'PAID', 'CLOSED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE orders_completion_facts (
    fact_business_id VARCHAR(64) NOT NULL,
    order_business_id VARCHAR(64) NOT NULL,
    fact_type VARCHAR(32) NOT NULL,
    fulfillment_fingerprint CHAR(64) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (fact_business_id),
    UNIQUE KEY uk_completion_fact_order (order_business_id, fact_type),
    CONSTRAINT ck_completion_fact_type CHECK (fact_type IN ('COMPLETED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
