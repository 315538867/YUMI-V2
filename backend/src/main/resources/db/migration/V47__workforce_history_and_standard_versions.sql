-- 人员与小组历史补全：小组状态事实、标准版本事实（基线 10 §7、§11）

CREATE TABLE workforce_team_status_facts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    fact_business_id VARCHAR(64) NOT NULL,
    team_business_id VARCHAR(64) NOT NULL,
    fact_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    reason VARCHAR(500) NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_team_status_fact (fact_business_id),
    KEY ix_team_status_fact_team (team_business_id, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE workforce_standard_version_facts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version_business_id VARCHAR(64) NOT NULL,
    standard_type VARCHAR(32) NOT NULL,
    standard_business_id VARCHAR(64) NOT NULL,
    content_version BIGINT NOT NULL,
    member_count INT NULL,
    efficiency_factor DECIMAL(12,8) NULL,
    effective_minutes INT NULL,
    reason VARCHAR(500) NULL,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_standard_version_fact (
        standard_type, standard_business_id, content_version),
    KEY ix_standard_version_lookup (standard_type, standard_business_id, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO workforce_team_status_facts (
    fact_business_id, team_business_id, fact_type, status, reason, occurred_at, operator_id)
SELECT CONCAT('WTSF-', UUID()), team_business_id, 'CREATE', status, NULL, created_at, created_by
FROM workforce_teams;

INSERT INTO workforce_standard_version_facts (
    version_business_id, standard_type, standard_business_id, content_version,
    member_count, efficiency_factor, effective_minutes, reason, occurred_at, operator_id)
SELECT CONCAT('WSV-', UUID()), 'EFFICIENCY', standard_business_id, content_version,
       member_count, efficiency_factor, NULL, NULL, updated_at, updated_by
FROM workforce_team_efficiency_standards;

INSERT INTO workforce_standard_version_facts (
    version_business_id, standard_type, standard_business_id, content_version,
    member_count, efficiency_factor, effective_minutes, reason, occurred_at, operator_id)
SELECT CONCAT('WSV-', UUID()), 'DAILY_WORK_TIME', standard_business_id, content_version,
       NULL, NULL, effective_minutes, NULL, updated_at, updated_by
FROM workforce_daily_work_time_standard;
