import type { MoneyWire } from '@/platform/services/money'
import type { CodeOf } from '@/platform/display/codeCatalog'

export type AfterSalesRouteType = CodeOf<'afterSales.routeType'>
export type ObligationType = CodeOf<'afterSales.obligationType'>
export type ObligationStatus = CodeOf<'afterSales.obligationStatus'>
export type ReturnDispositionType = CodeOf<'afterSales.dispositionType'>
export type PhysicalDestination = CodeOf<'production.physicalDestination'>
export type ResponsibilityType = CodeOf<'production.responsibilityType'>
export type ObligationChangeRoute = CodeOf<'afterSales.changeRoute'>

export interface ObligationView {
  obligationBusinessId: string
  obligationType: ObligationType
  bearerType: CodeOf<'afterSales.bearerType'>
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
  status: CodeOf<'afterSales.caseStatus'>
  openedAt: string
  contentVersion: number
  obligations: ObligationView[]
  completedAt: string | null
}

export interface ReceivedReturn {
  receiptItemBusinessId: string
  obligationBusinessId: string
  quantity: number
  conditionCode: CodeOf<'afterSales.conditionCode'>
}

export interface AfterSalesCostView {
  sourceBusinessId: string
  caseBusinessId: string
  orderBusinessId: string
  costCategory: CodeOf<'finance.costCategory'>
  amount: MoneyWire
  businessDate: string
  operationType: CodeOf<'finance.costOperation'>
  reasonText: string | null
  occurredAt: string
}
