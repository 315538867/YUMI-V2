-- 标准缺陷原因（一期不设等级，不根据原因自动计算工资或成本）

INSERT INTO production_defect_reasons (defect_reason_code, name, status, is_other, row_version) VALUES
    ('GLUE_OVERFLOW', '溢胶', 'ACTIVE', 0, 1),
    ('MISSING_PART', '缺件', 'ACTIVE', 0, 1),
    ('COLOR_MISMATCH', '颜色偏差', 'ACTIVE', 0, 1),
    ('BUBBLE', '气泡', 'ACTIVE', 0, 1),
    ('DEFORMED', '变形', 'ACTIVE', 0, 1),
    ('DIRTY', '脏污', 'ACTIVE', 0, 1),
    ('OTHER', '其他', 'ACTIVE', 1, 1);
