-- 报表来源游标、投影记录、失败与租约（基线 14 §6、§8）

CREATE TABLE reporting_feed_cursors (
    feed_code VARCHAR(32) NOT NULL,
    cursor_json VARCHAR(1000) NULL,
    status VARCHAR(16) NOT NULL,
    last_error VARCHAR(1000) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (feed_code),
    CONSTRAINT ck_reporting_cursor_status CHECK (status IN ('READY', 'FAILED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE reporting_projection_records (
    feed_code VARCHAR(32) NOT NULL,
    source_business_id VARCHAR(64) NOT NULL,
    source_version BIGINT NOT NULL,
    payload_json JSON NOT NULL,
    occurred_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (feed_code, source_business_id, source_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE reporting_feed_failures (
    failure_business_id VARCHAR(64) NOT NULL,
    feed_code VARCHAR(32) NOT NULL,
    reason_text VARCHAR(500) NOT NULL,
    last_error VARCHAR(1000) NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (failure_business_id),
    KEY ix_reporting_failure_feed (feed_code, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE reporting_feed_leases (
    feed_code VARCHAR(32) NOT NULL,
    owner_token VARCHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (feed_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
