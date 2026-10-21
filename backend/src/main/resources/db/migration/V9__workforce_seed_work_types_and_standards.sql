-- 工种与全局标准初始值（基线 10 §5.3）：一期只有一套全局每日有效工作时间标准

INSERT INTO workforce_work_types (work_type_code, name, active, row_version) VALUES
    ('MAKING', '制作', 1, 1),
    ('BAGGING', '捏毛装袋', 1, 1),
    ('SEAM_CUTTING', '缝边剪袋', 1, 1),
    ('OTHER_TASK', '其他任务', 1, 1);

INSERT INTO workforce_team_efficiency_standards (
    standard_business_id, member_count, efficiency_factor, content_version, row_version, updated_at, updated_by)
VALUES
    ('WFE-2', 2, 1.20000000, 1, 1, UTC_TIMESTAMP(6), 'SYSTEM'),
    ('WFE-3', 3, 1.30000000, 1, 1, UTC_TIMESTAMP(6), 'SYSTEM'),
    ('WFE-4', 4, 1.40000000, 1, 1, UTC_TIMESTAMP(6), 'SYSTEM');

INSERT INTO workforce_daily_work_time_standard (
    standard_business_id, effective_minutes, content_version, row_version, updated_at, updated_by)
VALUES ('WFD-DAILY', 480, 1, 1, UTC_TIMESTAMP(6), 'SYSTEM');
