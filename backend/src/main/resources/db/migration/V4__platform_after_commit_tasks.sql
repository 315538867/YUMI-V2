-- 平台提交后任务（基线 06 §6、§7）：租约、退避、人工恢复

CREATE TABLE platform_after_commit_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_business_id VARCHAR(64) NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    deduplication_key VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    next_run_at DATETIME(6) NOT NULL,
    lease_until DATETIME(6) NULL,
    lease_owner VARCHAR(128) NULL,
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_task_business_id (task_business_id),
    UNIQUE KEY uk_platform_task_dedup (task_type, deduplication_key),
    KEY ix_platform_task_dispatch (status, next_run_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
