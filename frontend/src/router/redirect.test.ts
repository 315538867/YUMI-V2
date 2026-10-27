import { describe, expect, it, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import router from '@/router'
import { useAuthStore } from '@/platform/stores/auth'

const permissions = [
  'customer.read',
  'product.read',
  'workforce.read',
  'production.read',
  'production.verify',
  'production.schedule',
  'production.correct',
  'order.read',
  'order.create',
  'order.legacy.intake',
  'aftersales.read',
  'payroll.read',
  'payroll.adjust',
  'payroll.glue-allowance.read',
  'payroll.glue-allowance.manage',
  'finance.read',
  'cost.read',
  'cost.accrue',
  'cost.allocate',
  'cost.adjust',
  'reporting.read',
  'reporting.admin',
  'platform.account.manage',
  'platform.audit.read',
  'platform.task.manage',
]

describe('一级菜单默认跳转', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    const auth = useAuthStore()
    auth.token = 'test-token'
    auth.account = {
      accountBusinessId: 'ACC-test',
      loginName: 'admin',
      sessionBusinessId: 'SES-test',
      permissions,
    }
    await router.push('/login')
  })

  it.each([
    ['/payroll/periods', '/reporting', '/reporting/monthly'],
    ['/production/schedule', '/finance', '/finance/daily-cash'],
    ['/settings/accounts', '/workforce', '/workforce/employees'],
    ['/customers', '/production', '/production/schedule'],
    ['/orders', '/payroll', '/payroll/periods'],
  ])('从 %s 进入 %s 应落到 %s', async (start, target, expected) => {
    await router.push(start)
    await router.push(target)

    expect(router.currentRoute.value.path).toBe(expected)
  })
})
