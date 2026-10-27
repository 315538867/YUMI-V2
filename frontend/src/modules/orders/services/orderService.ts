import { api } from '@/platform/services/api'
import { newIdempotencyKey } from '@/platform/services/idempotency'
import type {
  CreateOrderInput,
  OrderStatus,
  OrderSummaryView,
  OrderView,
  OrderWorkspaceView,
} from '@/modules/orders/contracts'

export interface ShipmentRequest {
  orderBusinessId: string
  items: Array<{
    agreementGroupBusinessId: string
    productionSourceBusinessId: string
    quantity: number
  }>
  trackingNo: string
  shippedAt: string
}

export const orderService = {
  list(
    keyword: string,
    status: OrderStatus | '',
    page = 1,
    size = 20,
  ): Promise<OrderSummaryView[]> {
    const search = new URLSearchParams({ page: String(page), size: String(size) })
    if (keyword.trim() !== '') {
      search.set('keyword', keyword.trim())
    }
    if (status !== '') {
      search.set('status', status)
    }
    return api.query<OrderSummaryView[]>(`/api/orders?${search.toString()}`)
  },

  get(orderBusinessId: string): Promise<OrderView> {
    return api.query<OrderView>(`/api/orders/${encodeURIComponent(orderBusinessId)}`)
  },

  workspace(orderBusinessId: string): Promise<OrderWorkspaceView> {
    return api.query<OrderWorkspaceView>(
      `/api/orders/${encodeURIComponent(orderBusinessId)}/workspace`,
    )
  },

  create(input: CreateOrderInput): Promise<{ orderBusinessId: string; orderNo: string }> {
    return api.command<{ orderBusinessId: string; orderNo: string }, CreateOrderInput>(
      '/api/orders',
      { idempotencyKey: newIdempotencyKey('order-create'), body: input },
    )
  },

  intake(input: Record<string, unknown>): Promise<{ orderBusinessId: string }> {
    return api.command<{ orderBusinessId: string }, Record<string, unknown>>(
      '/api/orders/opening-orders',
      { idempotencyKey: newIdempotencyKey('order-intake'), body: input },
    )
  },

  change(
    orderBusinessId: string,
    expectedContentVersion: number,
    actions: Array<Record<string, unknown>>,
    reasonText: string,
  ): Promise<unknown> {
    return api.command<unknown, Record<string, unknown>>(
      `/api/orders/${encodeURIComponent(orderBusinessId)}`,
      {
        method: 'PUT',
        idempotencyKey: newIdempotencyKey('order-change'),
        body: { expectedContentVersion, actions, reasonText },
      },
    )
  },

  ship(input: ShipmentRequest): Promise<{
    shipmentBusinessId: string
    deliveredQuantity: number
    deliveryFulfilled: boolean
  }> {
    return api.command('/api/orders/delivery/shipments', {
      idempotencyKey: newIdempotencyKey('order-shipment'),
      body: input,
    })
  },

  correctShipment(input: {
    shipmentBusinessId: string
    shipmentItemBusinessId: string
    quantityDecrease: number
    reasonText: string
  }): Promise<unknown> {
    return api.command('/api/orders/delivery/shipments/corrections', {
      idempotencyKey: newIdempotencyKey('shipment-correction'),
      body: input,
    })
  },

  complete(orderBusinessId: string): Promise<unknown> {
    return api.command(`/api/orders/${encodeURIComponent(orderBusinessId)}/completion`, {
      idempotencyKey: newIdempotencyKey('order-complete'),
      body: {},
    })
  },
}
