-- 静态数据种子（未上线阶段重写；类别 code 由系统固定，条目数据可由用户维护）
INSERT INTO star_levels (name, std_minutes, created_at, updated_at) VALUES
    ('一星', 5, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('二星', 10, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('三星', 15, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('四星', 20, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('五星', 30, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

-- 员工工种：四道工序的 code 由系统固定，名称可改、可停用，不允许新增或删除
INSERT INTO work_types (code, name, active, created_at, updated_at) VALUES
    ('MAKING', '制作', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('PACKING_BAG', '捏毛装袋', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('SEAM_CUTTING', '缝边剪袋', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('OTHER', '其他', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

-- 包装档位：系统预置 6 分钟档（对应真实核算表固定的 6×0.25 + 商品提成），其余档位由用户自建
INSERT INTO packaging_tiers (tier_name, std_minutes, created_at, updated_at) VALUES
    ('6 分钟档', 6.000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
