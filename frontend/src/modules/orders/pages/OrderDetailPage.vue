<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import type {
  OrderView,
  OrderWorkspacePayment,
  OrderWorkspaceShipment,
  OrderWorkspaceShipmentItem,
  OrderWorkspaceView,
} from '@/modules/orders/contracts'
import { orderService, type ShipmentRequest } from '@/modules/orders/services/orderService'
import { productionService } from '@/modules/production/services/productionService'
import { cashService } from '@/modules/finance/cash/services/cashService'
import { customerService } from '@/modules/customer/services/customerService'
import { productService } from '@/modules/product/services/productService'
import { fileService } from '@/platform/services/fileService'
import BusinessFileUpload from '@/shared/components/BusinessFileUpload.vue'
import CodeTag from '@/shared/components/CodeTag.vue'
import MoneyText from '@/shared/components/MoneyText.vue'
import PageState from '@/shared/components/PageState.vue'
import DateTimeText from '@/shared/components/DateTimeText.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'
import { formatDateTime } from '@/platform/display/dateTime'

interface TraceLedger {
  requiredQuantity?: number
  pendingWorkQuantity?: number
  inProcessQuantity?: number
  targetReadyQuantity?: number
  fulfilledQuantity?: number
  fulfillmentGapQuantity?: number
}

interface ProgressRow {
  demandBusinessId: string
  targetProcess: string
  required: number
  qualified: number
  shipped: number
  status: string
}

const route = useRoute()
const router = useRouter()
const orderId = String(route.params.orderBusinessId ?? '')
const order = ref<OrderView | null>(null)
const workspace = ref<OrderWorkspaceView | null>(null)
const customerName = ref('')
const productNames = ref<Record<string, string>>({})
const progressByItem = ref<Record<string, ProgressRow[]>>({})
const orderContentVersion = ref(0)
const activeTab = ref('agreements')
const loading = ref(true)
const errorText = ref('')

const paymentDialog = ref(false)
const paymentPreview = ref(false)
const paymentKind = ref<'payment' | 'refund'>('payment')
const paymentForm = reactive({ amount: '', businessDate: today() })
const voucherId = ref<string | null>(null)
const paying = ref(false)

const shipmentDialog = ref(false)
const shipmentForm = reactive({
  itemId: '',
  groupId: '',
  sourceId: '',
  quantity: 1,
  trackingNo: '',
  businessDate: today(),
})
const shipping = ref(false)

const correctionDialog = ref(false)
const correctionForm = reactive({
  shipmentBusinessId: '',
  shipmentItemBusinessId: '',
  quantityDecrease: 0,
  reasonText: '',
})
const correcting = ref(false)

const changeDialog = ref(false)
const changeForm = reactive({ unitPrice: '', reason: '' })
const changing = ref(false)

const voucherDialog = ref(false)
const voucherFiles = ref<Array<{ label: string; url: string }>>([])
const voucherLoading = ref(false)

const shipments = computed(() => workspace.value?.shipments ?? [])
const payments = computed(() => workspace.value?.payments ?? [])
const events = computed(() => workspace.value?.events ?? [])
const progressRows = computed(() =>
  (order.value?.items ?? []).flatMap((item) =>
    (progressByItem.value[item.orderItemBusinessId] ?? []).map((row) => ({
      ...row,
      productName: productNames.value[item.productBusinessId] ?? item.productBusinessId,
    })),
  ),
)

function today(): string {
  return new Date().toISOString().slice(0, 10)
}

function sourceLabel(value: string): string {
  return value === 'STUDIO' ? '工作室' : value === 'CUSTOMER' ? '客户' : value
}

function paymentLabel(payment: OrderWorkspacePayment): string {
  if (payment.reversesFactBusinessId) {
    return '冲销'
  }
  return payment.linkType === 'RECEIPT' ? '收款' : '退款'
}

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    const current = await orderService.workspace(orderId)
    workspace.value = current
    order.value = current.order

    try {
      customerName.value = (await customerService.get(current.order.customerBusinessId)).name
    } catch {
      customerName.value = current.order.customerBusinessId
    }

    const names: Record<string, string> = {}
    await Promise.all(
      current.order.items.map(async (item) => {
        try {
          names[item.productBusinessId] = (await productService.get(item.productBusinessId)).name
        } catch {
          names[item.productBusinessId] = item.productBusinessId
        }
      }),
    )
    productNames.value = names

    const shippedByItem: Record<string, number> = {}
    for (const shipment of current.shipments) {
      for (const item of shipment.items) {
        shippedByItem[item.orderItemBusinessId] =
          (shippedByItem[item.orderItemBusinessId] ?? 0) + item.effectiveQuantity
      }
    }

    const progress: Record<string, ProgressRow[]> = {}
    await Promise.all(
      current.order.items.map(async (item) => {
        try {
          const trace = await productionService.orderTrace(item.orderItemBusinessId)
          progress[item.orderItemBusinessId] = trace.demands.map((demand) => {
            const ledger = (demand.ledger ?? {}) as TraceLedger
            return {
              demandBusinessId: demand.demandBusinessId,
              targetProcess: demand.targetProcess,
              required: ledger.requiredQuantity ?? 0,
              qualified: ledger.fulfilledQuantity ?? ledger.targetReadyQuantity ?? 0,
              shipped: shippedByItem[item.orderItemBusinessId] ?? 0,
              status: demand.status,
            }
          })
        } catch {
          progress[item.orderItemBusinessId] = []
        }
      }),
    )
    progressByItem.value = progress

    const summaries = await orderService.list(current.order.orderNo, '')
    orderContentVersion.value =
      summaries.find((row) => row.orderBusinessId === orderId)?.contentVersion ?? 0
  } catch (error) {
    errorText.value = userMessageOf(error, '订单详情加载失败')
  } finally {
    loading.value = false
  }
}

function openPayment(kind: 'payment' | 'refund'): void {
  if (order.value === null) {
    return
  }
  paymentKind.value = kind
  const receivable = Number(order.value.fulfillment.currentReceivable.amount)
  const received = Number(order.value.fulfillment.receivedAmount.amount)
  const refunded = Number(order.value.fulfillment.refundedAmount.amount)
  paymentForm.amount =
    kind === 'payment'
      ? Math.max(receivable - received, 0).toFixed(2)
      : Math.max(received - refunded, 0).toFixed(2)
  paymentForm.businessDate = today()
  voucherId.value = null
  paymentDialog.value = true
}

function requestPaymentPreview(): void {
  if (!/^\d+(\.\d{1,2})?$/.test(paymentForm.amount) || Number(paymentForm.amount) <= 0) {
    errorText.value = '请填写大于 0 且最多两位小数的金额'
    return
  }
  errorText.value = ''
  paymentPreview.value = true
}

async function confirmPayment(): Promise<void> {
  if (order.value === null) {
    return
  }
  paying.value = true
  try {
    const vouchers = voucherId.value === null ? [] : [voucherId.value]
    if (paymentKind.value === 'payment') {
      await cashService.receiveOrder(
        orderId,
        paymentForm.amount,
        paymentForm.businessDate,
        vouchers,
      )
      ElMessage.success('收款已登记')
    } else {
      await cashService.refundOrder(orderId, paymentForm.amount, paymentForm.businessDate, vouchers)
      ElMessage.success('退款已登记')
    }
    paymentPreview.value = false
    paymentDialog.value = false
    await load()
  } catch (error) {
    errorText.value = userMessageOf(
      error,
      paymentKind.value === 'payment' ? '收款失败' : '退款失败',
    )
    paymentPreview.value = false
  } finally {
    paying.value = false
  }
}

async function openShipment(itemId?: string): Promise<void> {
  if (order.value === null || order.value.items.length === 0) {
    return
  }
  const item =
    order.value.items.find((row) => row.orderItemBusinessId === itemId) ?? order.value.items[0]!
  shipmentForm.itemId = item.orderItemBusinessId
  shipmentForm.groupId = item.agreement.agreementGroupBusinessId
  shipmentForm.quantity = item.activeQuantity - item.agreement.fulfilledQuantity
  shipmentForm.trackingNo = ''
  shipmentForm.businessDate = today()
  shipmentForm.sourceId = ''
  try {
    const trace = await productionService.orderTrace(item.orderItemBusinessId)
    shipmentForm.sourceId = trace.demands[0]?.productionSourceBusinessId ?? ''
  } catch {
    shipmentForm.sourceId = ''
  }
  shipmentDialog.value = true
}

async function submitShipment(): Promise<void> {
  if (order.value === null) {
    return
  }
  if (shipmentForm.sourceId.trim() === '' || shipmentForm.quantity <= 0) {
    errorText.value = '请填写生产来源和大于 0 的数量'
    return
  }
  shipping.value = true
  try {
    const request: ShipmentRequest = {
      orderBusinessId: orderId,
      items: [
        {
          agreementGroupBusinessId: shipmentForm.groupId,
          productionSourceBusinessId: shipmentForm.sourceId.trim(),
          quantity: Number(shipmentForm.quantity),
        },
      ],
      trackingNo: shipmentForm.trackingNo,
      shippedAt: `${shipmentForm.businessDate}T10:00:00`,
    }
    await orderService.ship(request)
    ElMessage.success('发货已确认')
    shipmentDialog.value = false
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '发货失败')
  } finally {
    shipping.value = false
  }
}

function openCorrection(shipment: OrderWorkspaceShipment, item: OrderWorkspaceShipmentItem): void {
  correctionForm.shipmentBusinessId = shipment.shipmentBusinessId
  correctionForm.shipmentItemBusinessId = item.shipmentItemBusinessId
  correctionForm.quantityDecrease = 0
  correctionForm.reasonText = ''
  correctionDialog.value = true
}

async function submitCorrection(): Promise<void> {
  if (correctionForm.quantityDecrease <= 0 || correctionForm.reasonText.trim() === '') {
    errorText.value = '请填写大于 0 的更正数量和原因'
    return
  }
  correcting.value = true
  try {
    await orderService.correctShipment({
      shipmentBusinessId: correctionForm.shipmentBusinessId,
      shipmentItemBusinessId: correctionForm.shipmentItemBusinessId,
      quantityDecrease: Number(correctionForm.quantityDecrease),
      reasonText: correctionForm.reasonText,
    })
    ElMessage.success('发货更正已提交')
    correctionDialog.value = false
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '发货更正失败')
  } finally {
    correcting.value = false
  }
}

function openChange(): void {
  if (order.value === null) {
    return
  }
  changeForm.unitPrice = order.value.items[0]?.agreement.unitPrice.amount ?? ''
  changeForm.reason = ''
  changeDialog.value = true
}

async function submitChange(): Promise<void> {
  if (order.value === null) {
    return
  }
  if (changeForm.unitPrice.trim() === '' || changeForm.reason.trim() === '') {
    errorText.value = '请填写新单价和变更原因'
    return
  }
  changing.value = true
  try {
    await orderService.change(
      orderId,
      orderContentVersion.value,
      [
        {
          kind: 'PRICE_OR_RECIPIENT',
          orderItemBusinessId: order.value.items[0]?.orderItemBusinessId,
          newUnitPrice: { amount: changeForm.unitPrice, currency: 'CNY' },
          newRecipient: null,
          newQuantity: null,
          newSchemeSnapshotRef: null,
          newTargetProcess: null,
          newMaterialSources: null,
          migratedQuantity: null,
        },
      ],
      changeForm.reason,
    )
    ElMessage.success('订单变更已提交')
    changeDialog.value = false
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '订单变更失败')
  } finally {
    changing.value = false
  }
}

async function complete(): Promise<void> {
  try {
    await orderService.complete(orderId)
    ElMessage.success('订单已完成')
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '订单完成失败')
  }
}

async function viewVouchers(payment: OrderWorkspacePayment): Promise<void> {
  voucherLoading.value = true
  voucherFiles.value = []
  try {
    const detail = await cashService.factDetail(payment.financeCashFactBusinessId)
    for (const [index, fileId] of detail.voucherFileRefs.entries()) {
      const blob = await fileService.readContent(fileId, 'finance.read')
      voucherFiles.value.push({
        label: `凭证 ${index + 1}`,
        url: URL.createObjectURL(blob),
      })
    }
    voucherDialog.value = true
  } catch (error) {
    errorText.value = userMessageOf(error, '凭证加载失败')
  } finally {
    voucherLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="order-detail">
    <PageState :loading="loading" :error="errorText" @retry="load">
      <template v-if="order">
        <header class="head">
          <div>
            <h1>{{ order.orderNo }}</h1>
            <span class="meta">
              <CodeTag category="order.origin" :code="order.origin" /> ·
              <CodeTag category="order.status" :code="order.status" /> · {{ order.orderDate }} →
              {{ order.expectedDeliveryDate }}
            </span>
          </div>
          <div class="actions">
            <PermissionButton permission="finance.cash.receive" @click="openPayment('payment')">
              登记收款
            </PermissionButton>
            <PermissionButton permission="finance.cash.receive" @click="openPayment('refund')">
              登记退款
            </PermissionButton>
            <PermissionButton permission="order.ship" @click="openShipment()">
              登记发货
            </PermissionButton>
            <PermissionButton permission="order.change" @click="openChange"
              >变更价格</PermissionButton
            >
            <PermissionButton permission="order.complete" type="primary" @click="complete">
              完成订单
            </PermissionButton>
          </div>
        </header>

        <el-descriptions :column="3" border>
          <el-descriptions-item label="客户">{{ customerName }}</el-descriptions-item>
          <el-descriptions-item label="当前应收"
            ><MoneyText :value="order.fulfillment.currentReceivable"
          /></el-descriptions-item>
          <el-descriptions-item label="已收"
            ><MoneyText :value="order.fulfillment.receivedAmount"
          /></el-descriptions-item>
          <el-descriptions-item label="已退"
            ><MoneyText :value="order.fulfillment.refundedAmount"
          /></el-descriptions-item>
          <el-descriptions-item label="交付履约">{{
            order.fulfillment.deliveryFulfilled ? '已满足' : '未满足'
          }}</el-descriptions-item>
          <el-descriptions-item label="付款履约">{{
            order.fulfillment.paymentFulfilled ? '已满足' : '未满足'
          }}</el-descriptions-item>
          <el-descriptions-item label="阻断原因" :span="3">
            {{ order.fulfillment.blockingReasons.join('；') || '无' }}
          </el-descriptions-item>
        </el-descriptions>

        <el-tabs v-model="activeTab" class="tabs">
          <el-tab-pane label="成交约定" name="agreements">
            <el-descriptions v-if="order.note" :column="1" border class="order-note">
              <el-descriptions-item label="订单说明">{{ order.note }}</el-descriptions-item>
            </el-descriptions>
            <el-table :data="order.items" border>
              <el-table-column label="商品" min-width="180">
                <template #default="{ row }">
                  {{ productNames[row.productBusinessId] ?? row.productBusinessId }}
                </template>
              </el-table-column>
              <el-table-column label="目标工序" width="120">
                <template #default="{ row }">
                  <CodeTag
                    category="production.targetProcess"
                    :code="row.agreement.targetProcess"
                  />
                </template>
              </el-table-column>
              <el-table-column label="数量 / 已交付" width="130">
                <template #default="{ row }">
                  {{ row.activeQuantity }} / {{ row.agreement.fulfilledQuantity }}
                </template>
              </el-table-column>
              <el-table-column label="单价" width="120">
                <template #default="{ row }"
                  ><MoneyText :value="row.agreement.unitPrice"
                /></template>
              </el-table-column>
              <el-table-column label="供料" min-width="200">
                <template #default="{ row }">
                  胶水 {{ sourceLabel(row.agreement.glueSource) }} · 色浆
                  {{ sourceLabel(row.agreement.colorPasteSource) }} · 袋子
                  {{ sourceLabel(row.agreement.bagSource) }} · 装饰
                  {{ sourceLabel(row.agreement.decorationSource) }}
                </template>
              </el-table-column>
              <el-table-column label="收货信息" min-width="220">
                <template #default="{ row }">
                  {{ row.agreement.recipient.customerName }} · {{ row.agreement.recipient.phone }} ·
                  {{ row.agreement.recipient.address }}
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="生产进度" name="production">
            <el-table :data="progressRows" border empty-text="暂无生产需求">
              <el-table-column prop="productName" label="商品" min-width="160" />
              <el-table-column prop="demandBusinessId" label="需求" min-width="200" />
              <el-table-column label="目标工序" width="120">
                <template #default="{ row }"
                  ><CodeTag category="production.targetProcess" :code="row.targetProcess"
                /></template>
              </el-table-column>
              <el-table-column prop="required" label="需求数量" width="100" />
              <el-table-column prop="qualified" label="合格完成" width="100" />
              <el-table-column prop="shipped" label="有效已发货" width="110" />
              <el-table-column label="需求状态" width="140">
                <template #default="{ row }">
                  <CodeTag category="production.demandStatus" :code="row.status" />
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="发货记录" name="shipments">
            <el-empty v-if="shipments.length === 0" description="尚无发货记录" />
            <el-card
              v-for="shipment in shipments"
              :key="shipment.shipmentBusinessId"
              class="record-card"
            >
              <template #header>
                <div class="record-head">
                  <strong>{{ shipment.shipmentNo }}</strong>
                  <span class="meta"
                    ><DateTimeText :value="shipment.shippedAt" /> ·
                    {{ shipment.trackingNo || '未填物流单号' }}</span
                  >
                  <el-tag :type="shipment.valid ? 'success' : 'info'" size="small">
                    {{ shipment.valid ? '有效' : '已更正' }}
                  </el-tag>
                </div>
              </template>
              <el-table :data="shipment.items" border size="small">
                <el-table-column prop="orderItemBusinessId" label="订单项" min-width="200" />
                <el-table-column
                  prop="productionSourceBusinessId"
                  label="生产来源"
                  min-width="190"
                />
                <el-table-column prop="quantity" label="原数量" width="90" />
                <el-table-column prop="effectiveQuantity" label="有效数量" width="100" />
                <el-table-column label="操作" width="100">
                  <template #default="{ row }">
                    <PermissionButton
                      permission="order.ship"
                      link
                      type="primary"
                      @click="openCorrection(shipment, row)"
                    >
                      更正
                    </PermissionButton>
                  </template>
                </el-table-column>
              </el-table>
              <el-table
                v-if="shipment.corrections.length"
                :data="shipment.corrections"
                border
                size="small"
                class="corrections"
              >
                <el-table-column label="更正类型" width="130">
                  <template #default="{ row }"
                    ><CodeTag category="order.correctionType" :code="row.correctionType"
                  /></template>
                </el-table-column>
                <el-table-column prop="quantityDelta" label="数量变化" width="100" />
                <el-table-column prop="reasonText" label="原因" min-width="220" />
                <el-table-column label="时间" width="200">
                  <template #default="{ row }"><DateTimeText :value="row.occurredAt" /></template>
                </el-table-column>
              </el-table>
            </el-card>
          </el-tab-pane>

          <el-tab-pane label="收退款记录" name="payments">
            <el-table :data="payments" border empty-text="尚无收退款记录">
              <el-table-column label="现金流水" min-width="180">
                <template #default="{ row }">{{ row.financeCashFactBusinessId }}</template>
              </el-table-column>
              <el-table-column label="类型" width="100">
                <template #default="{ row }">{{ paymentLabel(row) }}</template>
              </el-table-column>
              <el-table-column label="金额" width="130">
                <template #default="{ row }"><MoneyText :value="row.amount" /></template>
              </el-table-column>
              <el-table-column label="发生时间" width="200">
                <template #default="{ row }"><DateTimeText :value="row.occurredAt" /></template>
              </el-table-column>
              <el-table-column label="有效性" width="100">
                <template #default="{ row }">{{ row.valid ? '有效' : '已更正' }}</template>
              </el-table-column>
              <el-table-column label="凭证" width="120">
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    :loading="voucherLoading"
                    @click="viewVouchers(row)"
                  >
                    查看凭证
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="订单记录" name="history">
            <el-empty v-if="events.length === 0" description="暂无订单记录" />
            <el-timeline v-else>
              <el-timeline-item
                v-for="(event, index) in events"
                :key="`${event.kind}-${index}-${event.occurredAt}`"
                :timestamp="formatDateTime(event.occurredAt)"
                placement="top"
              >
                <strong>{{ event.title }}</strong>
                <CodeTag
                  v-if="event.kind === 'CHANGE' && event.code"
                  category="order.changeKind"
                  :code="event.code"
                />
                <p class="event-description">{{ event.description }}</p>
              </el-timeline-item>
            </el-timeline>
          </el-tab-pane>
        </el-tabs>

        <el-button class="back" @click="router.push('/orders')">返回列表</el-button>
      </template>

      <el-dialog
        v-model="paymentDialog"
        :title="paymentKind === 'payment' ? '登记订单实际收款' : '登记订单实际退款'"
        width="480px"
      >
        <el-form label-width="100px">
          <el-form-item label="本次金额"><el-input v-model="paymentForm.amount" /></el-form-item>
          <el-form-item label="业务日期">
            <el-date-picker
              v-model="paymentForm.businessDate"
              type="date"
              value-format="YYYY-MM-DD"
            />
          </el-form-item>
          <el-form-item label="凭证">
            <BusinessFileUpload
              v-model="voucherId"
              permission-code="finance.read"
              label="上传凭证（可选）"
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="paymentDialog = false">取消</el-button>
          <el-button type="primary" @click="requestPaymentPreview"
            >核对本次{{ paymentKind === 'payment' ? '收款' : '退款' }}</el-button
          >
        </template>
      </el-dialog>

      <el-dialog
        v-model="paymentPreview"
        :title="paymentKind === 'payment' ? '确认订单实际收款' : '确认订单实际退款'"
        width="440px"
      >
        <el-descriptions :column="1" border>
          <el-descriptions-item label="订单">{{ order?.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ customerName }}</el-descriptions-item>
          <el-descriptions-item label="本次金额"
            ><MoneyText :value="{ amount: paymentForm.amount, currency: 'CNY' }"
          /></el-descriptions-item>
          <el-descriptions-item label="业务日期">{{
            paymentForm.businessDate
          }}</el-descriptions-item>
          <el-descriptions-item label="凭证">{{
            voucherId ? '已上传' : '未附凭证'
          }}</el-descriptions-item>
        </el-descriptions>
        <template #footer>
          <el-button @click="paymentPreview = false">返回修改</el-button>
          <el-button type="primary" :loading="paying" @click="confirmPayment">
            确认已{{ paymentKind === 'payment' ? '收到款项' : '退回款项' }}
          </el-button>
        </template>
      </el-dialog>

      <el-dialog v-model="shipmentDialog" title="登记本次发货" width="480px">
        <el-form label-width="100px">
          <el-form-item label="交付约定"
            ><el-input v-model="shipmentForm.groupId" disabled
          /></el-form-item>
          <el-form-item label="生产来源"><el-input v-model="shipmentForm.sourceId" /></el-form-item>
          <el-form-item label="数量"
            ><el-input v-model.number="shipmentForm.quantity" type="number"
          /></el-form-item>
          <el-form-item label="物流单号"
            ><el-input v-model="shipmentForm.trackingNo"
          /></el-form-item>
          <el-form-item label="发货日期">
            <el-date-picker
              v-model="shipmentForm.businessDate"
              type="date"
              value-format="YYYY-MM-DD"
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="shipmentDialog = false">取消</el-button>
          <el-button type="primary" :loading="shipping" @click="submitShipment">确认发货</el-button>
        </template>
      </el-dialog>

      <el-dialog v-model="correctionDialog" title="更正发货记录" width="440px">
        <el-form label-width="100px">
          <el-form-item label="发货明细">
            <el-input v-model="correctionForm.shipmentItemBusinessId" disabled />
          </el-form-item>
          <el-form-item label="减少数量">
            <el-input v-model.number="correctionForm.quantityDecrease" type="number" />
          </el-form-item>
          <el-form-item label="更正原因">
            <el-input v-model="correctionForm.reasonText" type="textarea" :rows="2" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="correctionDialog = false">取消</el-button>
          <el-button type="primary" :loading="correcting" @click="submitCorrection"
            >提交更正</el-button
          >
        </template>
      </el-dialog>

      <el-dialog v-model="changeDialog" title="变更订单价格" width="440px">
        <el-form label-width="100px">
          <el-form-item label="新单价"><el-input v-model="changeForm.unitPrice" /></el-form-item>
          <el-form-item label="变更原因">
            <el-input v-model="changeForm.reason" type="textarea" :rows="2" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="changeDialog = false">取消</el-button>
          <el-button type="primary" :loading="changing" @click="submitChange">提交变更</el-button>
        </template>
      </el-dialog>

      <el-dialog v-model="voucherDialog" title="收付凭证" width="520px">
        <el-empty v-if="voucherFiles.length === 0" description="该记录未附凭证" />
        <div v-else class="voucher-list">
          <a v-for="file in voucherFiles" :key="file.url" :href="file.url" target="_blank">{{
            file.label
          }}</a>
        </div>
      </el-dialog>
    </PageState>
  </section>
</template>

<style scoped>
.order-detail {
  background: var(--yumi-surface);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 20px;
}

.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 16px;
}

h1 {
  margin: 0 0 4px;
  font-size: 20px;
}

.meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.tabs {
  margin-top: 16px;
}

.record-card {
  margin-bottom: 12px;
}

.record-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.corrections {
  margin-top: 8px;
}

.event-description {
  margin: 4px 0 0;
  color: var(--el-text-color-regular);
}

.back {
  margin-top: 12px;
}

.alert {
  margin-bottom: 12px;
}

.voucher-list {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.order-note {
  margin-bottom: 12px;
}
</style>
