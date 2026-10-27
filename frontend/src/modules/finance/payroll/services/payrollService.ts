import { api } from '@/platform/services/api'
import { newIdempotencyKey } from '@/platform/services/idempotency'
import { money } from '@/platform/services/money'
import type {
  EmployeePayrollView,
  GlueMonthReferenceView,
  GluePolicyView,
  PayrollPeriodRef,
} from '@/modules/finance/payroll/contracts'

export const payrollService = {
  periods(): Promise<PayrollPeriodRef[]> {
    return api.query<PayrollPeriodRef[]>('/api/payroll/periods')
  },

  createPeriod(input: {
    periodNo: string
    startDate: string
    endDate: string
  }): Promise<PayrollPeriodRef> {
    return api.command<PayrollPeriodRef, typeof input>('/api/payroll/periods', {
      idempotencyKey: newIdempotencyKey('payroll-period'),
      body: input,
    })
  },

  ingestSources(): Promise<{ laborCount: number; responsibilityCount: number }> {
    return api.command('/api/payroll/ingestions', {
      idempotencyKey: newIdempotencyKey('payroll-ingest'),
      body: {},
    })
  },

  calculate(
    periodBusinessId: string,
  ): Promise<{ employeeCount: number; referenceLineCount: number }> {
    return api.command('/api/payroll/calculations', {
      idempotencyKey: newIdempotencyKey('payroll-calculate'),
      body: { periodBusinessId },
    })
  },

  results(periodBusinessId: string): Promise<EmployeePayrollView[]> {
    return api.query<EmployeePayrollView[]>(
      `/api/payroll/periods/${encodeURIComponent(periodBusinessId)}/results`,
    )
  },

  result(periodBusinessId: string, employeeBusinessId: string): Promise<EmployeePayrollView> {
    return api.query<EmployeePayrollView>(
      `/api/payroll/periods/${encodeURIComponent(periodBusinessId)}/results/${encodeURIComponent(employeeBusinessId)}`,
    )
  },

  confirm(input: {
    resultBusinessId: string
    expectedRowVersion: number
    referenceFingerprint: string
    confirmedAmount: string
    note: string
    processingItems: Array<{
      referenceLineBusinessId: string
      manualAmount: string | null
      note: string | null
    }>
  }): Promise<unknown> {
    return api.command('/api/payroll/confirmations', {
      idempotencyKey: newIdempotencyKey('payroll-confirm'),
      body: {
        resultBusinessId: input.resultBusinessId,
        expectedRowVersion: input.expectedRowVersion,
        referenceFingerprint: input.referenceFingerprint,
        confirmedAmount: money(input.confirmedAmount),
        note: input.note,
        processingItems: input.processingItems.map((item) => ({
          referenceLineBusinessId: item.referenceLineBusinessId,
          manualAmount: item.manualAmount === null ? null : money(item.manualAmount),
          note: item.note,
        })),
      },
    })
  },

  review(resultBusinessId: string): Promise<unknown> {
    return api.command('/api/payroll/reviews', {
      idempotencyKey: newIdempotencyKey('payroll-review'),
      body: { resultBusinessId },
    })
  },

  adjust(input: {
    resultBusinessId: string
    amountDelta: string
    reasonText: string
    referenceRunBusinessId: string | null
  }): Promise<unknown> {
    return api.command('/api/payroll/adjustments', {
      idempotencyKey: newIdempotencyKey('payroll-adjust'),
      body: {
        resultBusinessId: input.resultBusinessId,
        amountDelta: { amount: input.amountDelta, currency: 'CNY' },
        reasonText: input.reasonText,
        referenceRunBusinessId: input.referenceRunBusinessId,
      },
    })
  },

  gluePolicy(month: string): Promise<GluePolicyView> {
    return api.query<GluePolicyView>(`/api/payroll/glue/policies?month=${month}`)
  },

  publishGluePolicy(input: {
    effectiveMonth: string
    exemptWeightGrams: string
    reasonText: string
    expectedGuardVersion: number
  }): Promise<GluePolicyView> {
    return api.command('/api/payroll/glue/policies', {
      idempotencyKey: newIdempotencyKey('glue-policy'),
      body: {
        effectiveMonth: input.effectiveMonth,
        exemptWeightGrams: input.exemptWeightGrams,
        reasonText: input.reasonText,
        expectedGuardVersion: input.expectedGuardVersion,
      },
    })
  },

  runGlueMonthReference(
    employeeBusinessId: string,
    monthStart: string,
  ): Promise<GlueMonthReferenceView> {
    return api.command('/api/payroll/glue/month-references', {
      idempotencyKey: newIdempotencyKey('glue-reference'),
      body: { employeeBusinessId, monthStart },
    })
  },

  glueMonthReference(employeeBusinessId: string, month: string): Promise<GlueMonthReferenceView> {
    return api.query<GlueMonthReferenceView>(
      `/api/payroll/glue/month-references/${month}?employeeBusinessId=${encodeURIComponent(employeeBusinessId)}`,
    )
  },
}
