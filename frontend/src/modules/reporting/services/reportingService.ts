import { api } from '@/platform/services/api'
import { newIdempotencyKey } from '@/platform/services/idempotency'
import type {
  AfterSalesMonthlySummary,
  FreshnessView,
  MonthlyOperatingSummary,
} from '@/modules/reporting/contracts'

export const reportingService = {
  themeSummary(month: string, theme: string): Promise<MonthlyOperatingSummary> {
    return api.query(`/api/reports/theme-summary?month=${month}&theme=${theme}`)
  },

  monthlySummary(month: string): Promise<MonthlyOperatingSummary> {
    return api.query<MonthlyOperatingSummary>(
      `/api/reporting/monthly-summary?month=${encodeURIComponent(month)}`,
    )
  },

  afterSalesSummary(month: string): Promise<AfterSalesMonthlySummary> {
    return api.query<AfterSalesMonthlySummary>(
      `/api/reporting/after-sales-summary?month=${encodeURIComponent(month)}`,
    )
  },

  freshness(): Promise<FreshnessView> {
    return api.query<FreshnessView>('/api/reporting/freshness')
  },

  ingest(): Promise<{ recordCount: number; failureCount: number }> {
    return api.command('/api/reporting/administration/ingestions', {
      idempotencyKey: newIdempotencyKey('report-ingest'),
      body: {},
    })
  },

  rebuild(): Promise<{
    versionBusinessId: string
    recordCount: number
    differenceCount: number
    activated: boolean
  }> {
    return api.command('/api/reporting/administration/rebuilds', {
      idempotencyKey: newIdempotencyKey('report-rebuild'),
      body: {},
    })
  },

  versions(): Promise<
    Array<{
      versionBusinessId: string
      status: string
      recordCount: number
      differenceCount: number
      builtAt: string | null
      activatedAt: string | null
    }>
  > {
    return api.query('/api/reporting/administration/versions')
  },

  differences(
    versionBusinessId: string,
  ): Promise<Array<{ differenceBusinessId: string; feedCode: string; detailText: string }>> {
    return api.query(
      `/api/reporting/administration/versions/${encodeURIComponent(versionBusinessId)}/differences`,
    )
  },
}
