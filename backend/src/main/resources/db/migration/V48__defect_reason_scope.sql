ALTER TABLE production_defect_reasons
    ADD COLUMN applicable_process VARCHAR(32) NULL AFTER is_other,
    ADD COLUMN requires_text TINYINT(1) NOT NULL DEFAULT 0 AFTER applicable_process;
