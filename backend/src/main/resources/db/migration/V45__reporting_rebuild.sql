-- 报表影子投影版本、对账差异与原子切换（基线 14 §6、§8）

CREATE TABLE reporting_projection_versions (
    version_business_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    record_count BIGINT NOT NULL,
    difference_count INT NOT NULL,
    built_at DATETIME(6) NOT NULL,
    activated_at DATETIME(6) NULL,
    PRIMARY KEY (version_business_id),
    CONSTRAINT ck_reporting_version_status CHECK (status IN ('BUILDING', 'ACTIVE', 'RETIRED', 'FAILED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE reporting_projection_version_records (
    version_business_id VARCHAR(64) NOT NULL,
    feed_code VARCHAR(32) NOT NULL,
    source_business_id VARCHAR(64) NOT NULL,
    source_version BIGINT NOT NULL,
    payload_json JSON NOT NULL,
    occurred_at DATETIME(6) NULL,
    PRIMARY KEY (version_business_id, feed_code, source_business_id, source_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE reporting_projection_active (
    singleton_id TINYINT NOT NULL,
    version_business_id VARCHAR(64) NULL,
    PRIMARY KEY (singleton_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE reporting_projection_differences (
    difference_business_id VARCHAR(64) NOT NULL,
    version_business_id VARCHAR(64) NOT NULL,
    feed_code VARCHAR(32) NOT NULL,
    detail_text VARCHAR(500) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (difference_business_id),
    KEY ix_reporting_difference_version (version_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
