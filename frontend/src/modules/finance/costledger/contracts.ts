export interface CostEntryView {
  costEntryBusinessId: string
  costCategory: string
  subjectType: string
  subjectBusinessId: string
  amount: string
  businessDate: string
  operationType: string
  allocated: boolean
  occurredAt: string
}

export interface CostSubjectSummary {
  subjectType: string
  subjectId: string
  totalAmount: string
  fullyAllocated: boolean
}

export interface CostFailureView {
  failureBusinessId: string
  sourceModule: string
  sourceType: string
  sourceBusinessId: string
  reasonText: string
  attempts: number
  lastError: string
  createdAt: string
}
