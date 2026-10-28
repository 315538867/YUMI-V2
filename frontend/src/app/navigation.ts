import type { Component } from 'vue'
import {
  DataAnalysis,
  Goods,
  Operation,
  Service,
  Setting,
  Tickets,
  TrendCharts,
  User,
  UserFilled,
  Wallet,
} from '@element-plus/icons-vue'

export interface NavigationItem {
  path: string
  title: string
  permission: string
  icon: Component
}

/** 统一图标导航：每个一级模块一个入口，名称通过工具提示与可访问标签给出。 */
export const navigationItems: NavigationItem[] = [
  { path: '/customers', title: '客户', permission: 'customer.read', icon: User },
  { path: '/products', title: '商品', permission: 'product.read', icon: Goods },
  { path: '/workforce', title: '人员与小组', permission: 'workforce.read', icon: UserFilled },
  { path: '/production', title: '生产', permission: 'production.read', icon: Operation },
  { path: '/orders', title: '订单', permission: 'order.read', icon: Tickets },
  { path: '/after-sales', title: '售后', permission: 'aftersales.read', icon: Service },
  { path: '/payroll', title: '工资', permission: 'payroll.read', icon: Wallet },
  { path: '/finance', title: '现金与成本', permission: 'finance.read', icon: DataAnalysis },
  { path: '/reporting', title: '报表', permission: 'reporting.read', icon: TrendCharts },
  {
    path: '/settings',
    title: '设置与账号',
    permission: 'platform.account.manage',
    icon: Setting,
  },
]
