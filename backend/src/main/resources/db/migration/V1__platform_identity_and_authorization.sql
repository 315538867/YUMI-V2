-- 平台身份与授权（基线 06 §7）
-- 账号与员工资料保持独立：本迁移不建立任何员工绑定字段。

CREATE TABLE platform_accounts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account_business_id VARCHAR(64) NOT NULL,
    login_name VARCHAR(64) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    failed_login_count INT NOT NULL DEFAULT 0,
    row_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_accounts_business_id (account_business_id),
    UNIQUE KEY uk_platform_accounts_login_name (login_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE platform_roles (
    id BIGINT NOT NULL AUTO_INCREMENT,
    role_business_id VARCHAR(64) NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    row_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_roles_business_id (role_business_id),
    UNIQUE KEY uk_platform_roles_code (role_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE platform_permissions (
    permission_code VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    PRIMARY KEY (permission_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE platform_role_permissions (
    role_business_id VARCHAR(64) NOT NULL,
    permission_code VARCHAR(64) NOT NULL,
    PRIMARY KEY (role_business_id, permission_code),
    CONSTRAINT fk_role_permissions_role FOREIGN KEY (role_business_id)
        REFERENCES platform_roles (role_business_id),
    CONSTRAINT fk_role_permissions_permission FOREIGN KEY (permission_code)
        REFERENCES platform_permissions (permission_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE platform_account_roles (
    account_business_id VARCHAR(64) NOT NULL,
    role_business_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (account_business_id, role_business_id),
    CONSTRAINT fk_account_roles_account FOREIGN KEY (account_business_id)
        REFERENCES platform_accounts (account_business_id),
    CONSTRAINT fk_account_roles_role FOREIGN KEY (role_business_id)
        REFERENCES platform_roles (role_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE platform_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_business_id VARCHAR(64) NOT NULL,
    account_business_id VARCHAR(64) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_sessions_business_id (session_business_id),
    UNIQUE KEY uk_platform_sessions_token_hash (token_hash),
    KEY ix_platform_sessions_account_expiry (account_business_id, expires_at),
    CONSTRAINT fk_sessions_account FOREIGN KEY (account_business_id)
        REFERENCES platform_accounts (account_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
