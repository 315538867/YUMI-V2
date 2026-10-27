import { api } from '@/platform/services/api'
import { newIdempotencyKey } from '@/platform/services/idempotency'
import { money } from '@/platform/services/money'
import type {
  AfterSalesCaseView,
  AfterSalesRouteType,
  ObligationChangeRoute,
  ObligationType,
  PhysicalDestination,
  ResponsibilityType,
  ReturnDispositionType,
} from '@/modules/orders/aftersales/contracts'

export const afterSalesService = {
  openCase(input: {
    orderBusinessId: string
    issueSummary: string
    routeType: AfterSalesRouteType
    obligations: Array<{
      obligationType: ObligationType
      targetQuantity: number | null
      targetAmount: { amount: string; currency: string } | null
      dueDate: string | null
      note: string | null
      orderItemBusinessId?: string | null
    }>
  }): Promise<{ caseBusinessId: string }> {
    return api.command('/api/after-sales/cases', {
      idempotencyKey: newIdempotencyKey('aftersales-open'),
      body: input,
    })
  },

  listCases(keyword: string, status: string): Promise<AfterSalesCaseView[]> {
    const search = new URLSearchParams({ page: '0', size: '50' })
    if (keyword.trim() !== '') {
      search.set('keyword', keyword.trim())
    }
    if (status !== '') {
      search.set('status', status)
    }
    return api.query<AfterSalesCaseView[]>(`/api/after-sales/cases?${search.toString()}`)
  },

  listCosts(
    caseBusinessId: string,
  ): Promise<import('@/modules/orders/aftersales/contracts').AfterSalesCostView[]> {
    return api.query(`/api/after-sales/costs?caseBusinessId=${encodeURIComponent(caseBusinessId)}`)
  },

  getCase(caseBusinessId: string): Promise<AfterSalesCaseView> {
    return api.query<AfterSalesCaseView>(
      `/api/after-sales/cases/${encodeURIComponent(caseBusinessId)}`,
    )
  },

  receiveReturn(input: {
    obligationBusinessId: string
    quantity: number
    conditionCode: string
  }): Promise<{ receiptItemBusinessId: string }> {
    return api.command('/api/after-sales/returns', {
      idempotencyKey: newIdempotencyKey('aftersales-return'),
      body: input,
    })
  },

  disposeReturnedProduct(input: {
    receiptItemBusinessId: string
    quantity: number
    dispositionType: ReturnDispositionType
    physicalDestination: PhysicalDestination
    businessDate: string
    reasonText: string
    responsibilityType: ResponsibilityType
    responsibleSubjectBusinessId: string | null
    waivedReason: string | null
    offlineInventoryName: string | null
    offlineInventoryNote: string | null
    handedOverBy: string | null
    receivedBy: string | null
    transferredAt: string | null
  }): Promise<{ dispositionBusinessId: string }> {
    return api.command('/api/after-sales/returns/dispositions', {
      idempotencyKey: newIdempotencyKey('aftersales-disposition'),
      body: input,
    })
  },

  returnToCustomer(input: {
    receiptItemBusinessId: string
    quantity: number
    deliveredAt: string
    recipient: { customerName: string; phone: string; address: string }
    trackingNo: string
  }): Promise<unknown> {
    return api.command('/api/after-sales/returns/return-to-customer', {
      idempotencyKey: newIdempotencyKey('aftersales-return-customer'),
      body: input,
    })
  },

  requestRepair(input: {
    obligationBusinessId: string
    receiptItemBusinessId: string
    quantity: number
  }): Promise<unknown> {
    return api.command('/api/after-sales/repairs', {
      idempotencyKey: newIdempotencyKey('aftersales-repair'),
      body: input,
    })
  },

  changeObligation(
    obligationBusinessId: string,
    input: {
      quantity: number
      route: ObligationChangeRoute
      amount: { amount: string; currency: string } | null
      note: string
    },
  ): Promise<unknown> {
    return api.command(
      `/api/after-sales/obligations/${encodeURIComponent(obligationBusinessId)}/changes`,
      {
        idempotencyKey: newIdempotencyKey('aftersales-obligation-change'),
        body: input,
      },
    )
  },

  cancelObligation(obligationBusinessId: string, input: Record<string, unknown>): Promise<unknown> {
    return api.command(
      `/api/after-sales/obligations/${encodeURIComponent(obligationBusinessId)}/cancellations`,
      { idempotencyKey: newIdempotencyKey('aftersales-obligation-cancel'), body: input },
    )
  },

  complete(caseBusinessId: string): Promise<unknown> {
    return api.command(`/api/after-sales/cases/${encodeURIComponent(caseBusinessId)}/completion`, {
      idempotencyKey: newIdempotencyKey('aftersales-complete'),
      body: {},
    })
  },

  confirmCost(input: {
    caseBusinessId: string
    costCategory: string
    amount: string
    businessDate: string
    reasonText: string
  }): Promise<{ sourceBusinessId: string }> {
    return api.command('/api/after-sales/costs/confirmations', {
      idempotencyKey: newIdempotencyKey('aftersales-cost'),
      body: {
        caseBusinessId: input.caseBusinessId,
        costCategory: input.costCategory,
        amount: money(input.amount),
        businessDate: input.businessDate,
        reasonText: input.reasonText,
      },
    })
  },

  correctCost(input: {
    caseBusinessId: string
    sourceBusinessId: string
    amount: string
    businessDate: string
    reasonText: string
  }): Promise<unknown> {
    return api.command('/api/after-sales/costs/corrections', {
      idempotencyKey: newIdempotencyKey('aftersales-cost-correction'),
      body: {
        caseBusinessId: input.caseBusinessId,
        sourceBusinessId: input.sourceBusinessId,
        amount: money(input.amount),
        businessDate: input.businessDate,
        reasonText: input.reasonText,
      },
    })
  },
}
