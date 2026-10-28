-- 返工来源活动占用（基线 11 §5.2.1、§6.7）：返工任务同样按活动明细预留来源数量。
-- 生产来源列继续保存返工来源所属的履约生产来源，返工来源列标识真正被占用的返工来源。

ALTER TABLE production_source_holds
    ADD COLUMN repair_source_business_id VARCHAR(64) NULL AFTER production_source_business_id,
    ADD KEY ix_repair_source_hold (repair_source_business_id, status);
