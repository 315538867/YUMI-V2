import type { MoneyWire } from '@/platform/services/money'

export type OrderStatus = 'CONFIRMED' | 'IN_FULFILLMENT' | 'COMPLETED' | 'CANCELLED'
export type OrderOrigin = 'NEW_ORDER' | 'AFTER_SALES_RESHIP' | 'LEGACY_INTAKE'
export type ProcessCode = 'MAKING' | 'BAGGING' | 'SEAM_CUTTING'
export type MaterialSource = 'STUDIO' | 'CUSTOMER'

export interface RecipientInfo {
  customerName: string
  phone: string
  address: string
}

export interface ProductSnapshotRef {
  snapshotType: string
  snapshotBusinessId: string
  ownerBusinessId: string
  snapshotVersion: number
  sourceContentVersion: number
  contentFingerprint: string
}

export interface OrderSummaryView {
  orderBusinessId: string
  orderNo: string
  origin: OrderOrigin
  status: OrderStatus
  customerBusinessId: string
  orderDate: string
  expectedDeliveryDate: string
  currentReceivable: MoneyWire
  deliveryFulfilled: boolean
  paymentFulfilled: boolean
  contentVersion: number
  updatedAt: string
}

export interface OrderView {
  orderBusinessId: string
  orderNo: string
  origin: OrderOrigin
  status: OrderStatus
  customerBusinessId: string
  customerSnapshotRef: string
  orderDate: string
  expectedDeliveryDate: string
  currentReceivableAmount: MoneyWire
  deliveryFulfilled: boolean
  paymentFulfilled: boolean
  items: Array<{
    orderItemBusinessId: string
    productBusinessId: string
    productSnapshotRef: string
    orderedQuantity: number
    activeQuantity: number
    status: string
    agreement: {
      agreementGroupBusinessId: string
      agreementVersionBusinessId: string
      versionNo: number
      schemeSnapshotRef: string
      targetProcess: ProcessCode
      unitPrice: MoneyWire
      quantity: number
      fulfilledQuantity: number
      productionBasisFingerprint: string
      recipient: RecipientInfo
    }
  }>
  fulfillment: {
    requiredQuantity: number
    shippedQuantity: number
    currentReceivable: MoneyWire
    receivedAmount: MoneyWire
    refundedAmount: MoneyWire
    deliveryFulfilled: boolean
    paymentFulfilled: boolean
    blockingReasons: string[]
  }
}

export interface OrderItemDraft {
  productBusinessId: string
  schemeSnapshotRef: ProductSnapshotRef
  targetProcess: ProcessCode
  quantity: number
  unitPrice: MoneyWire
  materialSources: {
    glue: MaterialSource
    colorPaste: MaterialSource
    bag: MaterialSource
    decoration: MaterialSource
  }
  recipient: RecipientInfo
}

export interface CreateOrderInput {
  customerBusinessId: string
  contactOverride: null
  orderDate: string
  expectedDeliveryDate: string
  items: OrderItemDraft[]
}
