import type { MoneyWire } from '@/platform/services/money'

export interface PayrollPeriodRef {
  periodBusinessId: string
  periodNo: string
  startDate: string
  endDate: string
  status: string
  contentVersion: number
}

export interface ReferenceLineView {
  lineBusinessId: string
  lineType: string
  sourceBusinessId: string
  componentCode: string
  quantity: number | null
  unitAmount: string | null
  referenceAmount?: MoneyWire | null
  occurred: boolean
  estimated: boolean
  materialSource: string | null
  incomplete: boolean
  note: string | null
}

export interface EmployeePayrollView {
  resultBusinessId: string
  periodBusinessId: string
  employeeBusinessId: string
  status: string
  referenceRunBusinessId: string
  referenceFingerprint: string
  confirmedAmount: MoneyWire | null
  payableAmount: MoneyWire | null
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
  status: string
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
