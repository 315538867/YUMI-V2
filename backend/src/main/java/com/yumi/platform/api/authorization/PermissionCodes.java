package com.yumi.platform.api.authorization;

/**
 * 一期稳定权限点。
 *
 * <p>代码即权限点，禁止按页面按钮名称判断权限。分组与基线 06、08—14 的权限表一致；
 * 新增权限点必须先更新基线，不能凭实现方便扩张。
 */
public final class PermissionCodes {

    // 平台
    public static final String PLATFORM_ACCOUNT_MANAGE = "platform.account.manage";
    public static final String PLATFORM_ROLE_MANAGE = "platform.role.manage";
    public static final String PLATFORM_AUDIT_READ = "platform.audit.read";
    public static final String PLATFORM_FILE_UPLOAD = "platform.file.upload";
    public static final String PLATFORM_TASK_MANAGE = "platform.task.manage";

    // 客户
    public static final String CUSTOMER_READ = "customer.read";
    public static final String CUSTOMER_MANAGE = "customer.manage";

    // 商品
    public static final String PRODUCT_READ = "product.read";
    public static final String PRODUCT_CREATE = "product.create";
    public static final String PRODUCT_UPDATE = "product.update";
    public static final String PRODUCT_DEACTIVATE = "product.deactivate";
    public static final String PRODUCT_SCHEME_MANAGE = "product.scheme.manage";
    public static final String PRODUCT_SNAPSHOT_READ = "product.snapshot.read";

    // 人员
    public static final String WORKFORCE_READ = "workforce.read";
    public static final String WORKFORCE_EMPLOYEE_MANAGE = "workforce.employee.manage";
    public static final String WORKFORCE_TEAM_MANAGE = "workforce.team.manage";
    public static final String WORKFORCE_STANDARD_MANAGE = "workforce.standard.manage";

    // 生产
    public static final String PRODUCTION_READ = "production.read";
    public static final String PRODUCTION_SCHEDULE = "production.schedule";
    public static final String PRODUCTION_VERIFY = "production.verify";
    public static final String PRODUCTION_DISPOSE = "production.dispose";
    public static final String PRODUCTION_CORRECT = "production.correct";
    public static final String PRODUCTION_DEFECT_REASON_MANAGE = "production.defect-reason.manage";
    public static final String PRODUCTION_RESPONSIBILITY_MANAGE = "production.responsibility.manage";
    public static final String PRODUCTION_CONSUME = "production.consume";
    public static final String PRODUCTION_TRANSFER = "production.transfer";

    // 订单与售后
    public static final String ORDER_READ = "order.read";
    public static final String ORDER_CREATE = "order.create";
    public static final String ORDER_CHANGE = "order.change";
    public static final String ORDER_CANCEL = "order.cancel";
    public static final String ORDER_SHIP = "order.ship";
    public static final String ORDER_COMPLETE = "order.complete";
    public static final String ORDER_LEGACY_INTAKE = "order.legacy.intake";
    public static final String AFTER_SALES_READ = "aftersales.read";
    public static final String AFTER_SALES_MANAGE = "aftersales.manage";

    // 工资
    public static final String PAYROLL_PERIOD_MANAGE = "payroll.period.manage";
    public static final String PAYROLL_CALCULATE = "payroll.calculate";
    public static final String PAYROLL_CONFIRM = "payroll.confirm";
    public static final String PAYROLL_ADJUST = "payroll.adjust";
    public static final String PAYROLL_READ = "payroll.read";
    /** 工资敏感读取：他人工资责任与金额，页面隐藏不构成保护。 */
    public static final String PAYROLL_SENSITIVE_READ = "payroll.sensitive.read";
    public static final String PAYROLL_GLUE_ALLOWANCE_MANAGE = "payroll.glue-allowance.manage";
    public static final String PAYROLL_GLUE_ALLOWANCE_READ = "payroll.glue-allowance.read";

    // 成本
    public static final String COST_ACCRUE = "cost.accrue";
    public static final String COST_ADJUST = "cost.adjust";
    public static final String COST_ALLOCATE = "cost.allocate";
    public static final String COST_READ = "cost.read";

    // 现金
    public static final String CASH_RECEIVE = "finance.cash.receive";
    public static final String CASH_PAY = "finance.cash.pay";
    public static final String CASH_REVERSE = "finance.cash.reverse";
    public static final String CASH_PAYROLL_RETURN = "finance.cash.payroll-return";
    public static final String FINANCE_READ = "finance.read";
    public static final String FINANCE_SENSITIVE_READ = "finance.sensitive.read";

    // 报表
    public static final String REPORTING_READ = "reporting.read";
    public static final String REPORTING_ADMIN = "reporting.admin";

    private static final java.util.Set<String> ALL = java.util.Set.of(
            PLATFORM_ACCOUNT_MANAGE, PLATFORM_ROLE_MANAGE, PLATFORM_AUDIT_READ,
            PLATFORM_FILE_UPLOAD, PLATFORM_TASK_MANAGE,
            CUSTOMER_READ, CUSTOMER_MANAGE,
            PRODUCT_READ, PRODUCT_CREATE, PRODUCT_UPDATE, PRODUCT_DEACTIVATE,
            PRODUCT_SCHEME_MANAGE, PRODUCT_SNAPSHOT_READ,
            WORKFORCE_READ, WORKFORCE_EMPLOYEE_MANAGE, WORKFORCE_TEAM_MANAGE,
            WORKFORCE_STANDARD_MANAGE,
            PRODUCTION_READ, PRODUCTION_SCHEDULE, PRODUCTION_VERIFY, PRODUCTION_DISPOSE,
            PRODUCTION_CORRECT, PRODUCTION_DEFECT_REASON_MANAGE, PRODUCTION_RESPONSIBILITY_MANAGE,
            PRODUCTION_CONSUME, PRODUCTION_TRANSFER,
            ORDER_READ, ORDER_CREATE, ORDER_CHANGE, ORDER_CANCEL, ORDER_SHIP, ORDER_COMPLETE,
            ORDER_LEGACY_INTAKE, AFTER_SALES_READ, AFTER_SALES_MANAGE,
            PAYROLL_PERIOD_MANAGE, PAYROLL_CALCULATE, PAYROLL_CONFIRM, PAYROLL_ADJUST,
            PAYROLL_READ, PAYROLL_SENSITIVE_READ, PAYROLL_GLUE_ALLOWANCE_MANAGE,
            PAYROLL_GLUE_ALLOWANCE_READ,
            COST_ACCRUE, COST_ADJUST, COST_ALLOCATE, COST_READ,
            CASH_RECEIVE, CASH_PAY, CASH_REVERSE, CASH_PAYROLL_RETURN, FINANCE_READ,
            FINANCE_SENSITIVE_READ,
            REPORTING_READ, REPORTING_ADMIN);

    private PermissionCodes() {
    }

    /** 全部已知权限点，用于角色维护时的合法性校验。 */
    public static java.util.Set<String> all() {
        return ALL;
    }

    public static boolean isKnown(String code) {
        return ALL.contains(code);
    }
}
