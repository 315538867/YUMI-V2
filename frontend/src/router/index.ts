import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { navigationGroups } from '@/app/navigation'
import { useAuthStore } from '@/platform/stores/auth'

const moduleRoutes: RouteRecordRaw[] = navigationGroups.flatMap((group) =>
  group.items
    .filter((item) => item.path !== '/customers' && item.path !== '/products' && item.path !== '/workforce' && item.path !== '/production' && item.path !== '/orders' && item.path !== '/after-sales' && item.path !== '/payroll' && item.path !== '/finance' && item.path !== '/reporting' && item.path !== '/settings')
    .map((item) => ({
    path: item.path.slice(1),
    name: item.path.slice(1).replaceAll('/', '-'),
    component: () => import('@/app/pages/ModulePlaceholderPage.vue'),
    meta: {
      requiresAuth: true,
      title: item.title,
      permission: item.permission,
    },
  })),
)

export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/platform/pages/LoginPage.vue'),
    meta: { requiresAuth: false },
  },
  {
    path: '/forbidden',
    name: 'forbidden',
    component: () => import('@/app/pages/ForbiddenPage.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/',
    component: () => import('@/app/AppLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      {
        path: '',
        name: 'home',
        component: () => import('@/app/HomePage.vue'),
        meta: { requiresAuth: true, title: '经营总览' },
      },
      {
        path: 'customers',
        name: 'customers',
        component: () => import('@/modules/customer/pages/CustomerListPage.vue'),
        meta: {
          requiresAuth: true,
          title: '客户',
          permission: 'customer.read',
        },
      },
      {
        path: 'customers/:customerBusinessId',
        name: 'customer-detail',
        component: () => import('@/modules/customer/pages/CustomerDetailPage.vue'),
        meta: {
          requiresAuth: true,
          title: '客户详情',
          permission: 'customer.read',
        },
      },
      {
        path: 'products',
        name: 'products',
        component: () => import('@/modules/product/pages/ProductListPage.vue'),
        meta: { requiresAuth: true, title: '商品', permission: 'product.read' },
      },
      {
        path: 'products/entry-settings',
        name: 'product-entry-settings',
        component: () => import('@/modules/product/pages/ProductEntrySettingsPage.vue'),
        meta: { requiresAuth: true, title: '商品录入设置', permission: 'product.read' },
      },
      {
        path: 'products/new',
        name: 'product-create',
        component: () => import('@/modules/product/pages/ProductEditorPage.vue'),
        meta: { requiresAuth: true, title: '新增商品', permission: 'product.create' },
      },
      {
        path: 'products/:productBusinessId',
        name: 'product-detail',
        component: () => import('@/modules/product/pages/ProductDetailPage.vue'),
        meta: { requiresAuth: true, title: '商品详情', permission: 'product.read' },
      },
      {
        path: 'products/:productBusinessId/edit',
        name: 'product-edit',
        component: () => import('@/modules/product/pages/ProductEditorPage.vue'),
        meta: { requiresAuth: true, title: '编辑商品', permission: 'product.update' },
      },
      {
        path: 'products/:productBusinessId/schemes/new',
        name: 'scheme-create',
        component: () => import('@/modules/product/pages/SchemeEditorPage.vue'),
        meta: { requiresAuth: true, title: '新建方案', permission: 'product.scheme.manage' },
      },
      {
        path: 'products/:productBusinessId/schemes/:schemeBusinessId',
        name: 'scheme-edit',
        component: () => import('@/modules/product/pages/SchemeEditorPage.vue'),
        meta: { requiresAuth: true, title: '编辑方案', permission: 'product.scheme.manage' },
      },
      {
        path: 'workforce',
        component: () => import('@/modules/workforce/pages/WorkforceLayout.vue'),
        meta: { requiresAuth: true, title: '人员与小组', permission: 'workforce.read' },
        redirect: '/workforce/employees',
        children: [
          {
            path: 'employees',
            name: 'workforce-employees',
            component: () => import('@/modules/workforce/pages/EmployeePage.vue'),
            meta: { requiresAuth: true, title: '员工与资格', permission: 'workforce.read' },
          },
          {
            path: 'teams',
            name: 'workforce-teams',
            component: () => import('@/modules/workforce/pages/TeamPage.vue'),
            meta: { requiresAuth: true, title: '小组', permission: 'workforce.read' },
          },
          {
            path: 'standards',
            name: 'workforce-standards',
            component: () => import('@/modules/workforce/pages/WorkforceStandardsPage.vue'),
            meta: { requiresAuth: true, title: '人员标准', permission: 'workforce.read' },
          },
          {
            path: 'activity-references',
            name: 'workforce-activity-references',
            component: () => import('@/modules/workforce/pages/ActivityReferencesPage.vue'),
            meta: { requiresAuth: true, title: '活动引用', permission: 'workforce.read' },
          },
        ],
      },
      {
        path: 'production',
        component: () => import('@/modules/production/pages/ProductionLayout.vue'),
        meta: { requiresAuth: true, title: '生产', permission: 'production.read' },
        redirect: '/production/schedule',
        children: [
          {
            path: 'schedule',
            name: 'production-schedule',
            component: () => import('@/modules/production/pages/TaskSchedulePage.vue'),
            meta: { requiresAuth: true, title: '生产排班', permission: 'production.read' },
          },
          {
            path: 'tasks/:taskBusinessId/adjust',
            name: 'production-task-adjust',
            component: () => import('@/modules/production/pages/TaskAdjustPage.vue'),
            meta: { requiresAuth: true, title: '任务调整', permission: 'production.schedule' },
          },
          {
            path: 'tasks/:taskBusinessId',
            name: 'production-task-detail',
            component: () => import('@/modules/production/pages/TaskDetailPage.vue'),
            meta: { requiresAuth: true, title: '任务详情', permission: 'production.read' },
          },
          {
            path: 'verify',
            name: 'production-verify',
            component: () => import('@/modules/production/pages/TaskVerificationPage.vue'),
            meta: { requiresAuth: true, title: '任务核验', permission: 'production.verify' },
          },
          {
            path: 'demands',
            name: 'production-demands',
            component: () => import('@/modules/production/pages/DemandGapPage.vue'),
            meta: { requiresAuth: true, title: '需求缺口', permission: 'production.read' },
          },
          {
            path: 'trace',
            name: 'production-trace',
            component: () => import('@/modules/production/pages/ProductionTracePage.vue'),
            meta: { requiresAuth: true, title: '订单追溯', permission: 'production.read' },
          },
          {
            path: 'quality',
            name: 'production-quality',
            component: () => import('@/modules/production/pages/QualityDispositionPage.vue'),
            meta: { requiresAuth: true, title: '质量与责任', permission: 'production.read' },
          },
          {
            path: 'corrections',
            name: 'production-corrections',
            component: () => import('@/modules/production/pages/ProductionCorrectionPage.vue'),
            meta: { requiresAuth: true, title: '生产事实更正', permission: 'production.correct' },
          },
          {
            path: 'defect-reasons',
            name: 'production-defect-reasons',
            component: () => import('@/modules/production/pages/DefectReasonsPage.vue'),
            meta: { requiresAuth: true, title: '缺陷原因', permission: 'production.read' },
          },
        ],
      },
      {
        path: 'orders',
        name: 'orders',
        component: () => import('@/modules/orders/pages/OrderListPage.vue'),
        meta: { requiresAuth: true, title: '订单', permission: 'order.read' },
      },
      {
        path: 'orders/new',
        name: 'order-create',
        component: () => import('@/modules/orders/pages/OrderEditorPage.vue'),
        meta: { requiresAuth: true, title: '新增订单', permission: 'order.create' },
      },
      {
        path: 'orders/opening',
        name: 'order-opening',
        component: () => import('@/modules/orders/pages/OpeningOrderPage.vue'),
        meta: { requiresAuth: true, title: '已有订单接管', permission: 'order.legacy.intake' },
      },
      {
        path: 'orders/:orderBusinessId',
        name: 'order-detail',
        component: () => import('@/modules/orders/pages/OrderDetailPage.vue'),
        meta: { requiresAuth: true, title: '订单详情', permission: 'order.read' },
      },
      {
        path: 'after-sales',
        name: 'after-sales',
        component: () => import('@/modules/orders/aftersales/pages/AfterSalesCasePage.vue'),
        meta: { requiresAuth: true, title: '售后', permission: 'aftersales.read' },
      },
      {
        path: 'payroll',
        component: () => import('@/modules/finance/payroll/pages/PayrollLayout.vue'),
        redirect: '/payroll/periods',
        meta: { requiresAuth: true, title: '工资', permission: 'payroll.read' },
        children: [
          {
            path: 'periods',
            name: 'payroll-periods',
            component: () => import('@/modules/finance/payroll/pages/PayrollPeriodsPage.vue'),
            meta: { requiresAuth: true, title: '工资期间', permission: 'payroll.read' },
          },
          {
            path: 'employees',
            name: 'payroll-employee',
            component: () => import('@/modules/finance/payroll/pages/PayrollEmployeePage.vue'),
            meta: { requiresAuth: true, title: '逐人工资', permission: 'payroll.read' },
          },
          {
            path: 'payments',
            name: 'payroll-payments',
            component: () => import('@/modules/finance/payroll/pages/PayrollPaymentPage.vue'),
            meta: { requiresAuth: true, title: '工资付款', permission: 'payroll.read' },
          },
          {
            path: 'adjustments',
            name: 'payroll-adjustments',
            component: () => import('@/modules/finance/payroll/pages/PayrollAdjustmentPage.vue'),
            meta: { requiresAuth: true, title: '工资调整', permission: 'payroll.adjust' },
          },
          {
            path: 'reviews',
            name: 'payroll-reviews',
            component: () => import('@/modules/finance/payroll/pages/PayrollReviewPage.vue'),
            meta: { requiresAuth: true, title: '工资复核', permission: 'payroll.read' },
          },
          {
            path: 'glue',
            name: 'payroll-glue',
            component: () => import('@/modules/finance/payroll/pages/GlueMonthReferencePage.vue'),
            meta: { requiresAuth: true, title: '胶水月参考', permission: 'payroll.glue-allowance.read' },
          },
        ],
      },
      {
        path: 'finance',
        component: () => import('@/modules/finance/pages/FinanceLayout.vue'),
        redirect: '/finance/daily-cash',
        meta: { requiresAuth: true, title: '现金与成本', permission: 'finance.read' },
        children: [
          {
            path: 'daily-cash',
            name: 'daily-cash',
            component: () => import('@/modules/finance/cash/pages/DailyCashPage.vue'),
            meta: { requiresAuth: true, title: '日常收支', permission: 'finance.read' },
          },
          {
            path: 'cash-history',
            name: 'cash-history',
            component: () => import('@/modules/finance/cash/pages/CashHistoryPage.vue'),
            meta: { requiresAuth: true, title: '现金流水', permission: 'finance.read' },
          },
          {
            path: 'cost-entries',
            name: 'cost-entries',
            component: () => import('@/modules/finance/costledger/pages/CostEntriesPage.vue'),
            meta: { requiresAuth: true, title: '成本明细', permission: 'cost.read' },
          },
          {
            path: 'cost-failures',
            name: 'cost-failures',
            component: () => import('@/modules/finance/costledger/pages/CostAccrualFailuresPage.vue'),
            meta: { requiresAuth: true, title: '归集失败', permission: 'cost.accrue' },
          },
          {
            path: 'cost-allocation',
            name: 'cost-allocation',
            component: () => import('@/modules/finance/costledger/pages/CostAllocationPage.vue'),
            meta: { requiresAuth: true, title: '成本分摊', permission: 'cost.allocate' },
          },
          {
            path: 'cost-adjustment',
            name: 'cost-adjustment',
            component: () => import('@/modules/finance/costledger/pages/CostAdjustmentPage.vue'),
            meta: { requiresAuth: true, title: '成本更正', permission: 'cost.adjust' },
          },
        ],
      },
      {
        path: 'reporting',
        component: () => import('@/modules/reporting/pages/ReportingLayout.vue'),
        redirect: '/reporting/monthly',
        meta: { requiresAuth: true, title: '报表', permission: 'reporting.read' },
        children: [
          {
            path: 'monthly',
            name: 'reporting-monthly',
            component: () => import('@/modules/reporting/pages/MonthlySummaryPage.vue'),
            meta: { requiresAuth: true, title: '月汇总', permission: 'reporting.read' },
          },
          {
            path: 'explorer',
            name: 'reporting-explorer',
            component: () => import('@/modules/reporting/pages/ReportExplorerPage.vue'),
            meta: { requiresAuth: true, title: '主题报表', permission: 'reporting.read' },
          },
          {
            path: 'reconciliation',
            name: 'reporting-reconciliation',
            component: () => import('@/modules/reporting/pages/ReportReconciliationPage.vue'),
            meta: { requiresAuth: true, title: '来源对账', permission: 'reporting.admin' },
          },
          {
            path: 'rebuild',
            name: 'reporting-rebuild',
            component: () => import('@/modules/reporting/pages/ReportRebuildPage.vue'),
            meta: { requiresAuth: true, title: '影子重建', permission: 'reporting.admin' },
          },
        ],
      },
      {
        path: 'settings',
        component: () => import('@/modules/platform-admin/pages/PlatformAdminLayout.vue'),
        redirect: '/settings/accounts',
        meta: { requiresAuth: true, title: '设置与账号', permission: 'platform.account.manage' },
        children: [
          {
            path: 'accounts',
            name: 'settings-accounts',
            component: () => import('@/modules/platform-admin/pages/AccountRolePage.vue'),
            meta: { requiresAuth: true, title: '账号与角色', permission: 'platform.account.manage' },
          },
          {
            path: 'glue',
            name: 'settings-glue',
            component: () => import('@/modules/platform-admin/pages/PayrollGluePolicyPage.vue'),
            meta: { requiresAuth: true, title: '胶水配置', permission: 'payroll.glue-allowance.manage' },
          },
          {
            path: 'audit',
            name: 'settings-audit',
            component: () => import('@/modules/platform-admin/pages/AuditSearchPage.vue'),
            meta: { requiresAuth: true, title: '审计检索', permission: 'platform.audit.read' },
          },
          {
            path: 'tasks',
            name: 'settings-tasks',
            component: () => import('@/modules/platform-admin/pages/AfterCommitTasksPage.vue'),
            meta: { requiresAuth: true, title: '提交后任务', permission: 'platform.task.manage' },
          },
        ],
      },
      ...moduleRoutes,
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/',
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth === false) {
    if (to.name === 'login' && auth.token !== null) {
      return { name: 'home' }
    }
    return true
  }
  if (auth.token === null) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (auth.account === null) {
    await auth.restore()
    if (auth.token === null) {
      return { name: 'login', query: { redirect: to.fullPath, expired: '1' } }
    }
  }
  const permission = to.meta.permission
  if (typeof permission === 'string' && !auth.hasPermission(permission)) {
    return { name: 'forbidden' }
  }
  return true
})

export default router
