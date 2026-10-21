-- 商品录入配置初始值：只作为录入时的预填建议，商品保存后使用自身内容版本（基线 09 §9.1）

INSERT INTO product_entry_defaults (
    config_key, glue_loss_rate, glue_unit_price, color_paste_unit_price, row_version, updated_at, updated_by)
VALUES ('DEFAULT', 0.05000000, 0.1000, 0.2000, 1, UTC_TIMESTAMP(6), 'SYSTEM');

INSERT INTO product_making_star_options (star_code, name, standard_minutes, sort_order, status, row_version)
VALUES
    ('STAR_1', '一星', 20, 1, 'ACTIVE', 1),
    ('STAR_2', '二星', 30, 2, 'ACTIVE', 1),
    ('STAR_3', '三星', 45, 3, 'ACTIVE', 1),
    ('STAR_4', '四星', 60, 4, 'ACTIVE', 1),
    ('STAR_5', '五星', 90, 5, 'ACTIVE', 1);

INSERT INTO product_process_time_options (
    option_business_id, process_code, option_code, name, standard_minutes, sort_order, status, row_version)
VALUES
    ('PTO-BAG-1', 'BAGGING', 'BAG_1', '捏毛装袋 5 分钟', 5, 1, 'ACTIVE', 1),
    ('PTO-BAG-2', 'BAGGING', 'BAG_2', '捏毛装袋 8 分钟', 8, 2, 'ACTIVE', 1),
    ('PTO-BAG-3', 'BAGGING', 'BAG_3', '捏毛装袋 12 分钟', 12, 3, 'ACTIVE', 1),
    ('PTO-SEAM-1', 'SEAM_CUTTING', 'SEAM_1', '缝边剪袋 4 分钟', 4, 1, 'ACTIVE', 1),
    ('PTO-SEAM-2', 'SEAM_CUTTING', 'SEAM_2', '缝边剪袋 6 分钟', 6, 2, 'ACTIVE', 1),
    ('PTO-SEAM-3', 'SEAM_CUTTING', 'SEAM_3', '缝边剪袋 10 分钟', 10, 3, 'ACTIVE', 1);
