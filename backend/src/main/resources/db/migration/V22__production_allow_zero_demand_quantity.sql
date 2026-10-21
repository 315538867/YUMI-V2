-- 订单减量/取消可以把需求数量降到 0；是否仍有效由 status 表达。
-- V10 建表时的 > 0 约束会阻止取消，这里改为 >= 0。

ALTER TABLE production_demands
    DROP CHECK ck_demand_quantity,
    ADD CONSTRAINT ck_demand_quantity CHECK (required_quantity >= 0);
