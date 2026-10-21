-- 员工、就业事实、工种资格、小组、标准、快照与活动引用（基线 10 §7）

CREATE TABLE workforce_employees (
    id BIGINT NOT NULL AUTO_INCREMENT,
    employee_business_id VARCHAR(64) NOT NULL,
    employee_no VARCHAR(32) NOT NULL,
    name VARCHAR(80) NOT NULL,
    phone VARCHAR(30) NULL,
    employment_status VARCHAR(32) NOT NULL,
    content_version BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_employee_business_id (employee_business_id),
    UNIQUE KEY uk_employee_no (employee_no),
    KEY ix_employee_status_name (employment_status, name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE workforce_employment_facts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    fact_business_id VARCHAR(64) NOT NULL,
    employee_business_id VARCHAR(64) NOT NULL,
    fact_type VARCHAR(32) NOT NULL,
    effective_date DATE NOT NULL,
    reason VARCHAR(500) NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_employment_fact (fact_business_id),
    KEY ix_employment_fact_employee (employee_business_id, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE workforce_work_types (
    work_type_code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    active TINYINT(1) NOT NULL DEFAULT 1,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (work_type_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE workforce_qualifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    employee_business_id VARCHAR(64) NOT NULL,
    work_type_code VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    effective_from DATE NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_qualification_employee_work_type (employee_business_id, work_type_code),
    KEY ix_qualification_work_type (work_type_code, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE workforce_qualification_facts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    fact_business_id VARCHAR(64) NOT NULL,
    employee_business_id VARCHAR(64) NOT NULL,
    work_type_code VARCHAR(32) NOT NULL,
    fact_type VARCHAR(32) NOT NULL,
    effective_date DATE NOT NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    occurred_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_qualification_fact (fact_business_id),
    KEY ix_qualification_fact_employee (employee_business_id, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE workforce_teams (
    id BIGINT NOT NULL AUTO_INCREMENT,
    team_business_id VARCHAR(64) NOT NULL,
    team_no VARCHAR(32) NOT NULL,
    name VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL,
    content_version BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_team_business_id (team_business_id),
    UNIQUE KEY uk_team_no (team_no),
    KEY ix_team_status_name (status, name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE workforce_team_members (
    id BIGINT NOT NULL AUTO_INCREMENT,
    team_business_id VARCHAR(64) NOT NULL,
    employee_business_id VARCHAR(64) NOT NULL,
    joined_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_team_member (team_business_id, employee_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE workforce_team_member_facts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    fact_business_id VARCHAR(64) NOT NULL,
    team_business_id VARCHAR(64) NOT NULL,
    employee_business_id VARCHAR(64) NOT NULL,
    fact_type VARCHAR(32) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    is_valid TINYINT(1) NOT NULL DEFAULT 1,
    operator_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_team_member_fact (fact_business_id),
    KEY ix_team_member_fact_team (team_business_id, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE workforce_team_efficiency_standards (
    standard_business_id VARCHAR(64) NOT NULL,
    member_count INT NOT NULL,
    efficiency_factor DECIMAL(12,8) NOT NULL,
    content_version BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    PRIMARY KEY (standard_business_id),
    UNIQUE KEY uk_efficiency_active_member_count (member_count),
    CONSTRAINT ck_efficiency_factor CHECK (efficiency_factor > 1),
    CONSTRAINT ck_efficiency_member_count CHECK (member_count >= 2)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE workforce_daily_work_time_standard (
    standard_business_id VARCHAR(64) NOT NULL,
    effective_minutes INT NOT NULL,
    content_version BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    PRIMARY KEY (standard_business_id),
    CONSTRAINT ck_daily_minutes CHECK (effective_minutes > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE workforce_employee_snapshots (
    snapshot_business_id VARCHAR(64) NOT NULL,
    employee_business_id VARCHAR(64) NOT NULL,
    snapshot_version BIGINT NOT NULL,
    source_content_version BIGINT NOT NULL,
    payload_json JSON NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (snapshot_business_id),
    UNIQUE KEY uk_employee_snapshot_version (employee_business_id, source_content_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE workforce_team_snapshots (
    snapshot_business_id VARCHAR(64) NOT NULL,
    team_business_id VARCHAR(64) NOT NULL,
    snapshot_version BIGINT NOT NULL,
    source_content_version BIGINT NOT NULL,
    payload_json JSON NOT NULL,
    member_refs_json JSON NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (snapshot_business_id),
    UNIQUE KEY uk_team_snapshot_version (team_business_id, source_content_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE workforce_standard_snapshots (
    snapshot_business_id VARCHAR(64) NOT NULL,
    standard_type VARCHAR(32) NOT NULL,
    standard_business_id VARCHAR(64) NOT NULL,
    snapshot_version BIGINT NOT NULL,
    source_content_version BIGINT NOT NULL,
    payload_json JSON NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (snapshot_business_id),
    UNIQUE KEY uk_standard_snapshot_version (
        standard_type, standard_business_id, source_content_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE workforce_activity_reference_guards (
    guard_type VARCHAR(32) NOT NULL,
    guarded_business_id VARCHAR(64) NOT NULL,
    active_reference_count INT NOT NULL DEFAULT 0,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (guard_type, guarded_business_id),
    CONSTRAINT ck_guard_count CHECK (active_reference_count >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE workforce_activity_reservations (
    reservation_business_id VARCHAR(64) NOT NULL,
    source_module VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_business_id VARCHAR(64) NOT NULL,
    reservation_scope_type VARCHAR(32) NOT NULL,
    reserved_business_id VARCHAR(64) NOT NULL,
    subject_snapshot_ref VARCHAR(64) NULL,
    standard_snapshot_ref VARCHAR(64) NULL,
    work_type_code VARCHAR(32) NULL,
    work_date DATE NULL,
    status VARCHAR(32) NOT NULL,
    reserved_at DATETIME(6) NOT NULL,
    released_at DATETIME(6) NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (reservation_business_id),
    UNIQUE KEY uk_activity_reservation_source (
        source_module, source_type, source_business_id, reservation_scope_type, reserved_business_id),
    KEY ix_activity_reservation_active (status, reserved_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
