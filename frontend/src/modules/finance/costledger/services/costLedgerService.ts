import { api } from '@/platform/services/api'
import { newIdempotencyKey } from '@/platform/services/idempotency'
import type {
  CostEntryView,
  CostFailureView,
  CostSubjectSummary,
} from '@/modules/finance/costledger/contracts'

function range(startDate: string, endDate: string): string {
  return `startDate=${startDate}&endDate=${endDate}`
}

export const costLedgerService = {
  entries(
    subjectType: string,
    subjectId: string,
    startDate: string,
    endDate: string,
  ): Promise<CostEntryView[]> {
    return api.query<CostEntryView[]>(
      `/api/finance/cost-ledger/entries?subjectType=${encodeURIComponent(subjectType)}&subjectId=${encodeURIComponent(subjectId)}&${range(startDate, endDate)}`,
    )
  },

  summary(
    subjectType: string,
    subjectId: string,
    startDate: string,
    endDate: string,
  ): Promise<CostSubjectSummary> {
    return api.query<CostSubjectSummary>(
      `/api/finance/cost-ledger/summary?subjectType=${encodeURIComponent(subjectType)}&subjectId=${encodeURIComponent(subjectId)}&${range(startDate, endDate)}`,
    )
  },

  failures(): Promise<CostFailureView[]> {
    return api.query<CostFailureView[]>('/api/finance/cost-ledger/failures')
  },

  retryFailure(
    failureBusinessId: string,
    startDate: string,
    endDate: string,
  ): Promise<{ accruedCount: number; failureCount: number }> {
    return api.command(
      `/api/finance/cost-ledger/failures/${encodeURIComponent(failureBusinessId)}/retry`,
      {
        idempotencyKey: newIdempotencyKey('cost-retry'),
        body: { startDate, endDate },
      },
    )
  },

  adjust(
    costEntryBusinessId: string,
    newAmount: string,
    reasonText: string,
  ): Promise<{ costEntryBusinessId: string }> {
    return api.command(
      `/api/finance/cost-ledger/entries/${encodeURIComponent(costEntryBusinessId)}/adjustments`,
      {
        idempotencyKey: newIdempotencyKey('cost-adjust'),
        body: { newAmount: { amount: newAmount, currency: 'CNY' }, reasonText },
      },
    )
  },

  allocate(
    costEntryBusinessId: string,
    allocations: Array<{
      subjectType: string
      subjectBusinessId: string
      amount: string
    }>,
  ): Promise<{ costEntryBusinessId: string }> {
    return api.command(
      `/api/finance/cost-ledger/entries/${encodeURIComponent(costEntryBusinessId)}/allocations`,
      {
        idempotencyKey: newIdempotencyKey('cost-allocate'),
        body: {
          allocations: allocations.map((item) => ({
            subjectType: item.subjectType,
            subjectBusinessId: item.subjectBusinessId,
            amount: { amount: item.amount, currency: 'CNY' },
          })),
        },
      },
    )
  },
}
