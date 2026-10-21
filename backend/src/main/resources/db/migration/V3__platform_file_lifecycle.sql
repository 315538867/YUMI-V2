-- 平台文件生命周期（基线 06 §6、§7）：临时上传 → 正式化 → 业务引用 → 删除保护

CREATE TABLE platform_files (
    id BIGINT NOT NULL AUTO_INCREMENT,
    file_business_id VARCHAR(64) NOT NULL,
    storage_key VARCHAR(255) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    row_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_files_business_id (file_business_id),
    UNIQUE KEY uk_platform_files_storage_key (storage_key),
    KEY ix_platform_files_sha256 (sha256),
    KEY ix_platform_files_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE platform_file_references (
    id BIGINT NOT NULL AUTO_INCREMENT,
    reference_business_id VARCHAR(64) NOT NULL,
    file_business_id VARCHAR(64) NOT NULL,
    owner_module VARCHAR(32) NOT NULL,
    owner_type VARCHAR(64) NOT NULL,
    owner_business_id VARCHAR(64) NOT NULL,
    purpose_code VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_file_reference (
        owner_module, owner_type, owner_business_id, purpose_code, file_business_id),
    UNIQUE KEY uk_platform_file_reference_business_id (reference_business_id),
    KEY ix_platform_file_reference_file (file_business_id),
    CONSTRAINT fk_file_reference_file FOREIGN KEY (file_business_id)
        REFERENCES platform_files (file_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
