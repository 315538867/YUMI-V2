INSERT IGNORE INTO platform_permissions (permission_code, name, resource_type) VALUES
    ('report.order.read', '报表-订单', 'reporting'),
    ('report.production.read', '报表-生产', 'reporting'),
    ('report.after-sales.read', '报表-售后', 'reporting'),
    ('report.payroll.read', '报表-工资', 'reporting'),
    ('report.cost.read', '报表-成本', 'reporting'),
    ('report.cash.read', '报表-现金', 'reporting');

INSERT IGNORE INTO platform_role_permissions (role_business_id, permission_code)
SELECT r.role_business_id, p.permission_code
FROM platform_roles r
JOIN platform_role_permissions rp
  ON rp.role_business_id = r.role_business_id AND rp.permission_code = 'reporting.read'
JOIN platform_permissions p
  ON p.permission_code IN ('report.order.read', 'report.production.read',
      'report.after-sales.read', 'report.payroll.read', 'report.cost.read',
      'report.cash.read');

INSERT IGNORE INTO platform_role_permissions (role_business_id, permission_code)
SELECT r.role_business_id, p.permission_code
FROM platform_roles r
JOIN platform_permissions p
  ON p.permission_code IN ('report.order.read', 'report.production.read',
      'report.after-sales.read', 'report.payroll.read', 'report.cost.read',
      'report.cash.read')
WHERE r.role_code = 'ADMIN';
