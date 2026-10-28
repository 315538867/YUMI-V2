import type { MoneyWire } from '@/platform/services/money'
import type { CodeOf } from '@/platform/display/codeCatalog'

export interface PayrollPeriodRef {
  periodBusinessId: string
  periodNo: string
  startDate: string
  endDate: string
  status: CodeOf<'finance.payrollPeriodStatus'>
  contentVersion: number
}

export interface ReferenceLineView {
  lineBusinessId: string
  lineType: CodeOf<'finance.costLineType'>
  sourceBusinessId: string
  componentCode: CodeOf<'finance.costComponent'>
  quantity: number | null
  unitAmount: string | null
  referenceAmount?: MoneyWire | null
  occurred: boolean
  estimated: boolean
  materialSource: CodeOf<'product.materialSource'> | null
  incomplete: boolean
  note: string | null
}

export interface EmployeePayrollView {
  resultBusinessId: string
  periodBusinessId: string
  employeeBusinessId: string
  employeeNo?: string | null
  employeeName?: string | null
  status: CodeOf<'finance.payrollResultStatus'>
  referenceRunBusinessId: string
  referenceFingerprint: string
  confirmedAmount: MoneyWire | null
  referenceAmount: MoneyWire | null
  payableAmount: MoneyWire | null
  paidAmount: MoneyWire | null
  remainingAmount: MoneyWire | null
  rowVersion: number
  lines: ReferenceLineView[]
}

export interface GluePolicyView {
  policyBusinessId: string
  effectiveMonth: string
  version: number
  exemptWeightGrams: string
  reasonText: string
}

export interface GlueMonthReferenceView {
  referenceBusinessId: string
  employeeBusinessId: string
  monthStart: string
  policyBusinessId: string
  status: CodeOf<'finance.glueReferenceStatus'>
  incompleteReason: string | null
  allocatedWeight: string
  exemptWeight: string
  referenceFingerprint: string
  stale: boolean
  lines: Array<{
    lineBusinessId: string
    responsibilityChainBusinessId: string
    costLineKey: string
    businessDate: string
    allocatedWeight: string
    exemptWeight: string
    chargeableWeight: string
    glueUnitPrice: string
    preAmount: string
    postAmount: string
  }>
}

export interface PayrollReviewView {
  reviewRunBusinessId: string
  periodBusinessId: string
  employeeBusinessId: string
  runType: string
  fingerprint: string
  generatedAt: string
  lines: ReferenceLineView[]
}
