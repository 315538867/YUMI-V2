INSERT IGNORE INTO platform_permissions (permission_code, name, resource_type)
VALUES ('production.task.override-over-standard', '超标准安排覆盖', 'production');

INSERT IGNORE INTO platform_role_permissions (role_business_id, permission_code)
SELECT role_business_id, 'production.task.override-over-standard'
FROM platform_roles
WHERE role_code = 'ADMIN';
