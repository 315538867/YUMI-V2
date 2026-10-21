export interface NavigationItem {
  path: string
  title: string
  permission: string
}

export interface NavigationGroup {
  title: string
  items: NavigationItem[]
}

/** 统一导航：一级分组只在侧栏展开，不另建二级菜单。 */
export const navigationGroups: NavigationGroup[] = [
  {
    title: '业务',
    items: [
      { path: '/customers', title: '客户', permission: 'customer.read' },
      { path: '/products', title: '商品', permission: 'product.read' },
      { path: '/workforce', title: '人员与小组', permission: 'workforce.read' },
      { path: '/production', title: '生产', permission: 'production.read' },
      { path: '/orders', title: '订单', permission: 'order.read' },
      { path: '/after-sales', title: '售后', permission: 'aftersales.read' },
    ],
  },
  {
    title: '财务',
    items: [
      { path: '/payroll', title: '工资', permission: 'payroll.read' },
      { path: '/finance', title: '现金与成本', permission: 'finance.read' },
    ],
  },
  {
    title: '分析与管理',
    items: [
      { path: '/reporting', title: '报表', permission: 'reporting.read' },
      { path: '/settings', title: '设置与账号', permission: 'platform.account.manage' },
    ],
  },
]
