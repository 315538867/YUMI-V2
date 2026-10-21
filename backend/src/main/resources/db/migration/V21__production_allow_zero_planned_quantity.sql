-- 任务明细被订单减量取消后计划数量归零是合法状态；是否仍有效由 verification_status 表达。
-- V11 建表时的 > 0 约束会阻止取消明细，这里改为 >= 0。

ALTER TABLE production_task_items
    DROP CHECK ck_task_item_quantity,
    ADD CONSTRAINT ck_task_item_quantity CHECK (planned_quantity >= 0);
