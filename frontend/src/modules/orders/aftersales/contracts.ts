import type { MoneyWire } from '@/platform/services/money'

export type AfterSalesRouteType =
  | 'REFUND_ONLY'
  | 'COMPENSATION_ONLY'
  | 'RETURN_AND_REWORK'
  | 'DIRECT_REPLACEMENT'
  | 'MIXED'
export type ObligationType =
  | 'REFUND'
  | 'COMPENSATION'
  | 'RETURN_AND_REPAIR'
  | 'REPLACEMENT'
  | 'CUSTOMER_COOPERATION'
export type ObligationStatus = 'OPEN' | 'PARTIALLY_FULFILLED' | 'FULFILLED' | 'CANCELLED'
export type ReturnDispositionType = 'REPAIR_REQUIRED' | 'SCRAPPED'
export type PhysicalDestination = 'IN_SYSTEM' | 'DESTROYED' | 'TRANSFERRED_OFFLINE_INVENTORY'
export type ResponsibilityType = 'EMPLOYEE' | 'TEAM' | 'STUDIO' | 'CUSTOMER' | 'WAIVED'
export type ObligationChangeRoute =
  | 'REFUND'
  | 'COMPENSATION'
  | 'REPAIR'
  | 'REPLACEMENT'
  | 'CUSTOMER_COOPERATION'

export interface ObligationView {
  obligationBusinessId: string
  obligationType: ObligationType
  bearerType: string
  targetQuantity: number | null
  targetAmount: MoneyWire | null
  dueDate: string | null
  note: string | null
  status: ObligationStatus
  rowVersion: number
}

export interface AfterSalesCaseView {
  caseBusinessId: string
  caseNo: string
  orderBusinessId: string
  customerBusinessId: string
  issueSummary: string
  routeType: AfterSalesRouteType
  status: string
  openedAt: string
  contentVersion: number
  obligations: ObligationView[]
  completedAt: string | null
}

export interface ReceivedReturn {
  receiptItemBusinessId: string
  obligationBusinessId: string
  quantity: number
  conditionCode: string
}

export interface AfterSalesCostView {
  sourceBusinessId: string
  caseBusinessId: string
  orderBusinessId: string
  costCategory: string
  amount: MoneyWire
  businessDate: string
  operationType: string
  reasonText: string | null
  occurredAt: string
}
