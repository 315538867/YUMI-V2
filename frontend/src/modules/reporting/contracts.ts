import type { CodeOf } from '@/platform/display/codeCatalog'

export interface MonthlyOperatingSummary {
  monthStart: string
  orderCount: number
  orderReceivable: string
  productionRequiredQuantity: number
  productionFulfilledQuantity: number
  productionGapQuantity: number
  productionInSystemQuantity: number
  productionConsumedQuantity: number
  destroyedQuantity: number
  offlineInventoryQuantity: number
  costTotal: string
  costIncomplete: boolean
  payrollPayable: string
  payrollPaid: string
  payrollCashReturn: string
  operatingIncome: string
  operatingExpense: string
  orderReceipts: string
  orderRefunds: string
  orderNetReceipts: string
  afterSalesPayments: string
  cashInflow: string
  cashOutflow: string
  cashNet: string
  lateSources: boolean
}

export interface FeedFreshness {
  feedCode: CodeOf<'reporting.feedCode'>
  status: CodeOf<'reporting.feedStatus'>
  lastError: string | null
  updatedAt: string | null
  lagSeconds: number
}

export interface FreshnessView {
  versionBusinessId: string | null
  activatedAt: string | null
  late: boolean
  feeds: FeedFreshness[]
}

export interface AfterSalesMonthlySummary {
  monthStart: string
  caseCount: number
  openCount: number
  completedCount: number
}
