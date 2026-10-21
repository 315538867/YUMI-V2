-- 日常收支输入明细（基线 13 §7.2、§8.4）

CREATE TABLE finance_daily_cash_details (
    daily_record_business_id VARCHAR(64) NOT NULL,
    cash_fact_business_id VARCHAR(64) NOT NULL,
    category_code VARCHAR(32) NOT NULL,
    description VARCHAR(500) NOT NULL,
    count_as_expense TINYINT(1) NOT NULL,
    cost_entry_business_id VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (daily_record_business_id),
    UNIQUE KEY uk_daily_cash_fact (cash_fact_business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
