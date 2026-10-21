-- 客户主数据、变化事实与快照（基线 08 §7）

CREATE TABLE customer_customers (
    id BIGINT NOT NULL AUTO_INCREMENT,
    customer_business_id VARCHAR(64) NOT NULL,
    customer_no VARCHAR(32) NOT NULL,
    name VARCHAR(80) NOT NULL,
    contact_name VARCHAR(40) NOT NULL,
    phone VARCHAR(30) NOT NULL,
    default_address VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    content_version BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_business_id (customer_business_id),
    UNIQUE KEY uk_customer_no (customer_no),
    KEY ix_customer_status_name (status, name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE customer_change_facts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    fact_business_id VARCHAR(64) NOT NULL,
    customer_business_id VARCHAR(64) NOT NULL,
    version_before BIGINT NOT NULL,
    version_after BIGINT NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    changed_fields_json JSON NULL,
    reason_text VARCHAR(500) NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    command_execution_id VARCHAR(64) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_change_fact (fact_business_id),
    KEY ix_customer_change (customer_business_id, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE customer_snapshots (
    id BIGINT NOT NULL AUTO_INCREMENT,
    snapshot_business_id VARCHAR(64) NOT NULL,
    customer_business_id VARCHAR(64) NOT NULL,
    snapshot_version BIGINT NOT NULL,
    source_content_version BIGINT NOT NULL,
    override_fingerprint CHAR(64) NOT NULL,
    customer_no VARCHAR(32) NOT NULL,
    name VARCHAR(80) NOT NULL,
    contact_name VARCHAR(40) NOT NULL,
    phone VARCHAR(30) NOT NULL,
    address VARCHAR(200) NOT NULL,
    content_fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by_command_execution_id VARCHAR(64) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_snapshot_business_id (snapshot_business_id),
    UNIQUE KEY uk_customer_snapshot_version (
        customer_business_id, source_content_version, override_fingerprint)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
