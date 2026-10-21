-- 成本分摊状态（基线 13 §6）

ALTER TABLE finance_cost_entries
    ADD COLUMN allocated TINYINT(1) NOT NULL DEFAULT 0 AFTER operation_type;
