CREATE TABLE after_sales_amount_decisions (
    decision_business_id VARCHAR(64) NOT NULL,
    case_business_id VARCHAR(64) NOT NULL,
    obligation_business_id VARCHAR(64) NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    note VARCHAR(500) NOT NULL,
    decided_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (decision_business_id),
    KEY ix_amount_decision_case (case_business_id, decided_at),
    KEY ix_amount_decision_obligation (obligation_business_id, is_valid),
    CONSTRAINT ck_amount_decision_amount CHECK (amount >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
