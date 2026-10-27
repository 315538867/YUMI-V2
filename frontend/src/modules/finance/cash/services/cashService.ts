import { api } from '@/platform/services/api'
import { newIdempotencyKey } from '@/platform/services/idempotency'
import { money } from '@/platform/services/money'
import type {
  CashFactDetailView,
  CashFactRef,
  CashFactView,
  CashPeriodSummary,
} from '@/modules/finance/cash/contracts'

export const cashService = {
  factDetail(cashFactBusinessId: string): Promise<CashFactDetailView> {
    return api.query<CashFactDetailView>(
      `/api/finance/cash/facts/${encodeURIComponent(cashFactBusinessId)}`,
    )
  },

  receiveOrder(
    orderBusinessId: string,
    amount: string,
    businessDate: string,
    voucherFileRefs: string[] = [],
  ): Promise<CashFactRef> {
    return api.command<CashFactRef, Record<string, unknown>>(
      '/api/finance/cash/order-receipts',
      {
        idempotencyKey: newIdempotencyKey('order-receipt'),
        body: { orderBusinessId, amount: money(amount), businessDate, voucherFileRefs },
      },
    )
  },

  refundOrder(
    orderBusinessId: string,
    amount: string,
    businessDate: string,
    voucherFileRefs: string[] = [],
  ): Promise<CashFactRef> {
    return api.command<CashFactRef, Record<string, unknown>>(
      '/api/finance/cash/order-refunds',
      {
        idempotencyKey: newIdempotencyKey('order-refund'),
        body: { orderBusinessId, amount: money(amount), businessDate, voucherFileRefs },
      },
    )
  },

  refundAfterSales(
    obligationBusinessId: string,
    amount: string,
    businessDate: string,
    voucherFileRefs: string[] = [],
  ): Promise<CashFactRef> {
    return api.command<CashFactRef, Record<string, unknown>>(
      '/api/finance/cash/after-sales-payments',
      {
        idempotencyKey: newIdempotencyKey('aftersales-payment'),
        body: { obligationBusinessId, amount: money(amount), businessDate, voucherFileRefs },
      },
    )
  },

  payPayroll(
    allocations: Array<{ resultBusinessId: string; amount: string }>,
    businessDate: string,
  ): Promise<CashFactRef> {
    return api.command<
      CashFactRef,
      { allocations: Array<{ resultBusinessId: string; amount: unknown }>; businessDate: string }
    >('/api/finance/cash/payroll-payments', {
      idempotencyKey: newIdempotencyKey('payroll-payment'),
      body: {
        allocations: allocations.map((item) => ({
          resultBusinessId: item.resultBusinessId,
          amount: money(item.amount),
        })),
        businessDate,
      },
    })
  },

  recordDailyCash(input: {
    direction: 'IN' | 'OUT'
    categoryCode: string
    businessDate: string
    amount: string
    description: string
    countAsExpense: boolean
    voucherFileRefs?: string[]
  }): Promise<{ dailyRecordBusinessId: string }> {
    return api.command<
      { dailyRecordBusinessId: string },
      {
        direction: 'IN' | 'OUT'
        categoryCode: string
        businessDate: string
        amount: unknown
        description: string
        countAsExpense: boolean
        voucherFileRefs: string[]
      }
    >('/api/finance/cash/daily-cash', {
      idempotencyKey: newIdempotencyKey('daily-cash'),
      body: {
        direction: input.direction,
        categoryCode: input.categoryCode,
        businessDate: input.businessDate,
        amount: money(input.amount),
        description: input.description,
        countAsExpense: input.countAsExpense,
        voucherFileRefs: input.voucherFileRefs ?? [],
      },
    })
  },

  returnPayroll(input: {
    originalCashFactBusinessId: string
    resultBusinessId: string
    amount: string
    businessDate: string
    reasonText: string
  }): Promise<CashFactRef> {
    return api.command<CashFactRef, Record<string, unknown>>(
      '/api/finance/cash/payroll-returns',
      {
        idempotencyKey: newIdempotencyKey('payroll-return'),
        body: {
          originalCashFactBusinessId: input.originalCashFactBusinessId,
          resultBusinessId: input.resultBusinessId,
          amount: money(input.amount),
          businessDate: input.businessDate,
          reasonText: input.reasonText,
        },
      },
    )
  },

  reverse(
    cashFactBusinessId: string,
    businessDate: string,
    reasonText: string,
  ): Promise<CashFactRef> {
    return api.command<CashFactRef, { cashFactBusinessId: string; businessDate: string; reasonText: string }>(
      '/api/finance/cash/reversals',
      {
        idempotencyKey: newIdempotencyKey('cash-reversal'),
        body: { cashFactBusinessId, businessDate, reasonText },
      },
    )
  },

  facts(startDate: string, endDate: string, cashType = ''): Promise<CashFactView[]> {
    const type = cashType === '' ? '' : `&cashType=${encodeURIComponent(cashType)}`
    return api.query<CashFactView[]>(
      `/api/finance/cash/facts?startDate=${startDate}&endDate=${endDate}${type}`,
    )
  },

  periodSummary(startDate: string, endDate: string): Promise<CashPeriodSummary> {
    return api.query<CashPeriodSummary>(
      `/api/finance/cash/period-summary?startDate=${startDate}&endDate=${endDate}`,
    )
  },
}
