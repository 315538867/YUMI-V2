-- 责任更正历史：REVERSE 旧链 + APPLY 新链，物理数量不变（基线 11 §7.6）

CREATE TABLE production_responsibility_revisions (
    revision_business_id VARCHAR(64) NOT NULL,
    responsibility_chain_business_id VARCHAR(64) NOT NULL,
    disposition_fact_business_id VARCHAR(64) NOT NULL,
    effect_type VARCHAR(16) NOT NULL,
    responsibility_type VARCHAR(32) NOT NULL,
    responsible_subject_business_id VARCHAR(64) NULL,
    waived_reason VARCHAR(200) NULL,
    quantity INT NOT NULL,
    business_date DATE NOT NULL,
    reason_text VARCHAR(500) NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    feed_sequence BIGINT NOT NULL AUTO_INCREMENT,
    PRIMARY KEY (revision_business_id),
    UNIQUE KEY uk_responsibility_feed_sequence (feed_sequence),
    KEY ix_revision_disposition (disposition_fact_business_id, occurred_at),
    CONSTRAINT ck_revision_effect CHECK (effect_type IN ('REVERSE', 'APPLY')),
    CONSTRAINT ck_revision_type CHECK (
        responsibility_type IN ('EMPLOYEE', 'TEAM', 'STUDIO', 'CUSTOMER', 'WAIVED')),
    CONSTRAINT ck_revision_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
