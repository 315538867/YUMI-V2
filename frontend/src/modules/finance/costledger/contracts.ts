import type { CodeOf } from '@/platform/display/codeCatalog'

export interface CostEntryView {
  costEntryBusinessId: string
  costCategory: CodeOf<'finance.costCategory'>
  subjectType: CodeOf<'finance.costSubjectType'>
  subjectBusinessId: string
  amount: string
  businessDate: string
  operationType: CodeOf<'finance.costOperation'>
  sourceModule: string
  sourceType: string
  sourceBusinessId: string
  basisRef: string | null
  reasonText: string | null
  valid: boolean
  allocated: boolean
  occurredAt: string
}

export interface CostSubjectSummary {
  subjectType: CodeOf<'finance.costSubjectType'>
  subjectId: string
  totalAmount: string
  fullyAllocated: boolean
}

export interface CostFailureView {
  failureBusinessId: string
  sourceModule: CodeOf<'finance.costSourceModule'>
  sourceType: CodeOf<'finance.costSourceType'>
  sourceBusinessId: string
  reasonText: string
  attempts: number
  lastError: string
  createdAt: string
}
