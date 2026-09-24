-- 包装提成默认值（元/件）：商品表单取此全局值作默认、可修改（2026-09-24 用户确认，对应真实核算表的“打包提成”参数）
INSERT INTO catalog_settings (setting_key, setting_value, created_at, updated_at)
VALUES ('packaging_commission_default', 0.000000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
