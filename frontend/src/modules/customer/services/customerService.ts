import { api } from '@/platform/services/api'
import { newIdempotencyKey } from '@/platform/services/idempotency'
import type {
  CreateCustomerInput,
  CustomerChangeView,
  CustomerStatus,
  CustomerView,
  UpdateCustomerInput,
} from '@/modules/customer/contracts'

function query(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && String(value).trim() !== '') {
      search.set(key, String(value))
    }
  }
  const text = search.toString()
  return text === '' ? '' : `?${text}`
}

export const customerService = {
  list(keyword: string, status: CustomerStatus | '', page = 1, size = 20): Promise<CustomerView[]> {
    return api.query<CustomerView[]>(`/api/customers${query({ keyword, status, page, size })}`)
  },

  get(customerBusinessId: string): Promise<CustomerView> {
    return api.query<CustomerView>(`/api/customers/${encodeURIComponent(customerBusinessId)}`)
  },

  history(customerBusinessId: string): Promise<CustomerChangeView[]> {
    return api.query<CustomerChangeView[]>(
      `/api/customers/${encodeURIComponent(customerBusinessId)}/history`,
    )
  },

  create(input: CreateCustomerInput): Promise<CustomerView> {
    return api.command<CustomerView, CreateCustomerInput>('/api/customers', {
      idempotencyKey: newIdempotencyKey('customer-create'),
      body: input,
    })
  },

  update(customerBusinessId: string, input: UpdateCustomerInput): Promise<CustomerView> {
    return api.command<CustomerView, UpdateCustomerInput>(
      `/api/customers/${encodeURIComponent(customerBusinessId)}`,
      {
        method: 'PUT',
        idempotencyKey: newIdempotencyKey('customer-update'),
        body: input,
      },
    )
  },

  changeStatus(
    customerBusinessId: string,
    targetStatus: CustomerStatus,
    expectedRowVersion: number,
  ): Promise<CustomerView> {
    return api.command<CustomerView, { targetStatus: CustomerStatus; expectedRowVersion: number }>(
      `/api/customers/${encodeURIComponent(customerBusinessId)}/status`,
      {
        idempotencyKey: newIdempotencyKey('customer-status'),
        body: { targetStatus, expectedRowVersion },
      },
    )
  },
}
