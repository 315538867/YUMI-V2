-- 现金冲销追溯与 Feed 序号（基线 13 §7.1）

ALTER TABLE finance_cash_facts
    ADD COLUMN feed_sequence BIGINT NOT NULL AUTO_INCREMENT UNIQUE AFTER cash_fact_business_id;

ALTER TABLE finance_payroll_payment_returns
    ADD COLUMN operation_type VARCHAR(16) NOT NULL DEFAULT 'ACCRUE' AFTER amount,
    ADD COLUMN corrects_return_link_business_id VARCHAR(64) NULL AFTER operation_type,
    ADD COLUMN is_valid TINYINT(1) NOT NULL DEFAULT 1 AFTER corrects_return_link_business_id;

ALTER TABLE finance_payroll_return_details
    ADD COLUMN operation_type VARCHAR(16) NOT NULL DEFAULT 'ACCRUE' AFTER reason_text,
    ADD COLUMN corrects_return_detail_business_id VARCHAR(64) NULL AFTER operation_type,
    ADD COLUMN is_valid TINYINT(1) NOT NULL DEFAULT 1 AFTER corrects_return_detail_business_id;
