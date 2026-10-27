import type { MoneyWire } from '@/platform/services/money'
import type { CodeOf } from '@/platform/display/codeCatalog'

export type OrderStatus = CodeOf<'order.status'>
export type OrderOrigin = CodeOf<'order.origin'>
export type ProcessCode = CodeOf<'product.targetProcess'>
export type MaterialSource = CodeOf<'product.materialSource'>

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
  netReceipts: MoneyWire
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
  note: string | null
  currentReceivableAmount: MoneyWire
  deliveryFulfilled: boolean
  paymentFulfilled: boolean
  items: Array<{
    orderItemBusinessId: string
    productBusinessId: string
    productSnapshotRef: string
    orderedQuantity: number
    activeQuantity: number
    status: CodeOf<'order.itemStatus'>
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
      glueSource: MaterialSource
      colorPasteSource: MaterialSource
      bagSource: MaterialSource
      decorationSource: MaterialSource
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

export interface OrderWorkspaceShipmentItem {
  shipmentItemBusinessId: string
  orderItemBusinessId: string
  productionSourceBusinessId: string
  quantity: number
  effectiveQuantity: number
}

export interface OrderWorkspaceCorrection {
  factBusinessId: string
  shipmentItemBusinessId: string
  correctionType: CodeOf<'order.correctionType'>
  quantityDelta: number
  reasonText: string
  occurredAt: string
  valid: boolean
}

export interface OrderWorkspaceShipment {
  shipmentBusinessId: string
  shipmentNo: string
  shippedAt: string
  trackingNo: string | null
  valid: boolean
  items: OrderWorkspaceShipmentItem[]
  corrections: OrderWorkspaceCorrection[]
}

export interface OrderWorkspacePayment {
  factBusinessId: string
  financeCashFactBusinessId: string
  linkType: CodeOf<'order.paymentType'>
  amount: MoneyWire
  reversesFactBusinessId: string | null
  valid: boolean
  occurredAt: string
}

export interface OrderWorkspaceEvent {
  kind: CodeOf<'order.eventKind'>
  code: CodeOf<'order.changeKind'> | null
  occurredAt: string
  title: string
  description: string
}

export interface OrderWorkspaceView {
  order: OrderView
  shipments: OrderWorkspaceShipment[]
  payments: OrderWorkspacePayment[]
  events: OrderWorkspaceEvent[]
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
  note: string | null
  items: OrderItemDraft[]
}
