import type { MoneyWire } from '@/platform/services/money'

export interface CashFactRef {
  cashFactBusinessId: string
  cashNo: string
  direction: 'IN' | 'OUT'
  cashType: string
  amount: MoneyWire
  businessDate: string
}

export interface CashFactView {
  cashFactBusinessId: string
  cashNo: string
  direction: 'IN' | 'OUT'
  cashType: string
  businessDate: string
  amount: string
  valid: boolean
}

export interface CashPeriodSummary {
  startDate: string
  endDate: string
  income: string
  outflow: string
  netAmount: string
}
