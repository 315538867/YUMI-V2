-- 取消/减量到 0 是合法状态：数量由 status 表达是否仍有效，历史版本不得被改写。
-- V18 建表时的 > 0 约束会阻止取消整项，这里改为 >= 0。

ALTER TABLE orders_agreement_groups
    DROP CHECK ck_agreement_group_quantity,
    ADD CONSTRAINT ck_agreement_group_quantity CHECK (
        active_quantity >= 0 AND fulfilled_quantity >= 0);

ALTER TABLE orders_agreement_versions
    DROP CHECK ck_agreement_version_quantity,
    ADD CONSTRAINT ck_agreement_version_quantity CHECK (quantity >= 0);
