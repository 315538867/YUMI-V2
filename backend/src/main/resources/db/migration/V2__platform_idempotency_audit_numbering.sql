-- 平台幂等、审计与编号（基线 06 §7、04 §1）

CREATE TABLE platform_idempotency_records (
    id BIGINT NOT NULL AUTO_INCREMENT,
    scope_key VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_type VARCHAR(64) NULL,
    result_business_id VARCHAR(64) NULL,
    expires_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_idempotency (scope_key, idempotency_key),
    KEY ix_platform_idempotency_expiry (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE platform_audit_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    audit_business_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    account_business_id VARCHAR(64) NULL,
    module_code VARCHAR(64) NOT NULL,
    action_code VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NULL,
    target_business_id VARCHAR(64) NULL,
    result_code VARCHAR(64) NOT NULL,
    note_of_audit_business_id VARCHAR(64) NULL,
    detail_json JSON NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_audit_business_id (audit_business_id),
    KEY ix_platform_audit_target (target_type, target_business_id, occurred_at),
    KEY ix_platform_audit_request (request_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE platform_business_sequences (
    sequence_code VARCHAR(64) NOT NULL,
    next_value BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (sequence_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
