-- 活动任务来源占用（基线 11 §5.2.1）：来源可安排数量 = 来源可用总量 − 活动任务预留
-- 预留行与守卫计数同事务维护；释放只改状态与计数，不删除历史。

CREATE TABLE production_source_reservations (
    production_source_business_id VARCHAR(64) NOT NULL,
    reserved_quantity INT NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (production_source_business_id),
    CONSTRAINT ck_source_reservation_nonnegative CHECK (reserved_quantity >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE production_source_holds (
    task_item_business_id VARCHAR(64) NOT NULL,
    task_business_id VARCHAR(64) NOT NULL,
    production_source_business_id VARCHAR(64) NOT NULL,
    reserved_quantity INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    reserved_at DATETIME(6) NOT NULL,
    released_at DATETIME(6) NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (task_item_business_id),
    KEY ix_source_hold_source (production_source_business_id, status),
    CONSTRAINT ck_source_hold_quantity CHECK (reserved_quantity > 0),
    CONSTRAINT ck_source_hold_status CHECK (status IN ('ACTIVE', 'RELEASED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
