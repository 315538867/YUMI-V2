-- 成本来源 Feed 序号与经营费用（基线 13 §6、§8.4）

ALTER TABLE finance_cost_entries
    ADD COLUMN feed_sequence BIGINT NOT NULL AUTO_INCREMENT UNIQUE AFTER cost_entry_business_id;
