import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { installElementPlus } from '@/platform/i18n/elementLocale'
import { ApiClientError, ApiErrorTypes } from '@/platform/services/apiClient'
import PayrollEmployeePage from '@/modules/finance/payroll/pages/PayrollEmployeePage.vue'
import PayrollGluePolicyPage from '@/modules/platform-admin/pages/PayrollGluePolicyPage.vue'

const result = vi.fn()
const gluePolicy = vi.fn()

vi.mock('@/modules/finance/payroll/services/payrollService', () => ({
  payrollService: {
    result: (...args: unknown[]) => result(...args),
    gluePolicy: (...args: unknown[]) => gluePolicy(...args),
    publishGluePolicy: vi.fn(),
  },
}))

function elementPlusPlugin() {
  return {
    install: (app: Parameters<typeof installElementPlus>[0]) => installElementPlus(app),
  }
}

async function mountEmployeePage(path = '/payroll/employees') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/payroll/employees', component: PayrollEmployeePage },
      { path: '/payroll/periods', component: { template: '<div>periods</div>' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  return mount(PayrollEmployeePage, {
    global: { plugins: [elementPlusPlugin(), router] },
  })
}

describe('工资空态', () => {
  beforeEach(() => {
    result.mockReset()
    gluePolicy.mockReset()
  })

  it('工资员工页缺少期间或员工参数时不请求接口，显示友好空态', async () => {
    const wrapper = await mountEmployeePage()
    await flushPromises()

    expect(result).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请从工资期间页选择员工')
  })

  it('工资参考行缺少金额时显示占位而不是抛错', async () => {
    result.mockResolvedValue({
      resultBusinessId: 'RES-test',
      periodBusinessId: 'PYP-test',
      employeeBusinessId: 'EMP-test',
      status: 'REFERENCE_READY',
      referenceRunBusinessId: 'RUN-test',
      referenceFingerprint: 'FP-test',
      confirmedAmount: null,
      payableAmount: null,
      rowVersion: 1,
      lines: [
        {
          lineBusinessId: 'LINE-test',
          lineType: 'MATERIAL',
          sourceBusinessId: 'SRC-test',
          componentCode: 'SEAM_OTHER',
          quantity: 1,
          unitAmount: '0.00',
          occurred: true,
          estimated: false,
          materialSource: null,
          incomplete: false,
          note: null,
        },
      ],
    })
    const wrapper = await mountEmployeePage('/payroll/employees?period=PYP-test&employee=EMP-test')
    await flushPromises()

    expect(wrapper.text()).toContain('—')
  })

  it('胶水配置页未配置时显示提示，不显示加载失败', async () => {
    gluePolicy.mockRejectedValue(
      new ApiClientError({
        type: ApiErrorTypes.NOT_FOUND,
        code: 'PAYROLL_GLUE_POLICY_MISSING',
        message: '该月尚无胶水设置',
      }),
    )
    const wrapper = mount(PayrollGluePolicyPage, {
      global: { plugins: [elementPlusPlugin()] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('该月尚未配置胶水减免')
    expect(wrapper.text()).not.toContain('胶水配置加载失败')
  })
})
