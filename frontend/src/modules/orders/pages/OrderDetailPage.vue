<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { useAuthStore } from '@/platform/stores/auth'
import type { OrderView } from '@/modules/orders/contracts'
import { orderService, type ShipmentRequest } from '@/modules/orders/services/orderService'
import { productionService } from '@/modules/production/services/productionService'
import { cashService } from '@/modules/finance/cash/services/cashService'
import BusinessFileUpload from '@/shared/components/BusinessFileUpload.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const orderId = String(route.params.orderBusinessId ?? '')
const order = ref<OrderView | null>(null)
const orderContentVersion = ref(0)
const loading = ref(true)
const errorText = ref('')
const dialog = ref<'payment' | 'refund' | 'shipment' | 'change' | ''>('')
const dialogVisible = ref(false)
const voucherId = ref<string | null>(null)
const dialogTitles = {
  payment: '登记收款',
  refund: '登记退款',
  shipment: '确认发货',
  change: '变更价格',
} as const
const dialogTitle = computed(() => (dialog.value === '' ? '' : dialogTitles[dialog.value]))
const activeItemId = ref('')
const form = reactive({
  amount: '',
  businessDate: new Date().toISOString().slice(0, 10),
  groupId: '',
  sourceId: '',
  quantity: 1,
  trackingNo: '',
  unitPrice: '',
  reason: '',
})

async function load(): Promise<void> {
  loading.value = true
  try {
    order.value = await orderService.get(orderId)
    const summaries = await orderService.list(order.value.orderNo, '')
    orderContentVersion.value =
      summaries.find((row) => row.orderBusinessId === orderId)?.contentVersion ?? 0
  } catch (error) {
    errorText.value = userMessageOf(error, '订单详情加载失败')
  } finally {
    loading.value = false
  }
}

function openPayment(kind: 'payment' | 'refund' | 'change'): void {
  if (order.value === null) {
    return
  }
  dialog.value = kind
  form.amount = order.value.fulfillment.currentReceivable.amount
  form.businessDate = new Date().toISOString().slice(0, 10)
  form.unitPrice = order.value.items[0]?.agreement.unitPrice.amount ?? ''
  form.reason = ''
  voucherId.value = null
  dialogVisible.value = true
}

async function openShipment(itemId: string): Promise<void> {
  const item = order.value?.items.find((row) => row.orderItemBusinessId === itemId)
  if (item === undefined) {
    return
  }
  activeItemId.value = itemId
  dialog.value = 'shipment'
  form.groupId = item.agreement.agreementGroupBusinessId
  form.quantity = item.activeQuantity - item.agreement.fulfilledQuantity
  form.trackingNo = ''
  form.sourceId = ''
  try {
    const trace = await productionService.orderTrace(itemId)
    form.sourceId = trace.demands[0]?.productionSourceBusinessId ?? ''
  } catch {
    form.sourceId = ''
  }
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  if (order.value === null) {
    return
  }
  errorText.value = ''
  try {
    if (dialog.value === 'payment') {
      await cashService.receiveOrder(
        orderId,
        form.amount,
        form.businessDate,
        voucherId.value === null ? [] : [voucherId.value],
      )
      ElMessage.success('收款已登记')
    } else if (dialog.value === 'refund') {
      await cashService.refundOrder(
        orderId,
        form.amount,
        form.businessDate,
        voucherId.value === null ? [] : [voucherId.value],
      )
      ElMessage.success('退款已登记')
    } else if (dialog.value === 'shipment') {
      const request: ShipmentRequest = {
        orderBusinessId: orderId,
        items: [
          {
            agreementGroupBusinessId: form.groupId,
            productionSourceBusinessId: form.sourceId,
            quantity: Number(form.quantity),
          },
        ],
        trackingNo: form.trackingNo,
        shippedAt: `${form.businessDate}T10:00:00`,
      }
      await orderService.ship(request)
      ElMessage.success('发货已确认')
    } else if (dialog.value === 'change') {
      await orderService.change(
        orderId,
        orderContentVersion.value,
        [
          {
            kind: 'PRICE_OR_RECIPIENT',
            orderItemBusinessId: order.value.items[0]?.orderItemBusinessId,
            newUnitPrice: { amount: form.unitPrice, currency: 'CNY' },
            newRecipient: null,
            newQuantity: null,
            newSchemeSnapshotRef: null,
            newTargetProcess: null,
            newMaterialSources: null,
            migratedQuantity: null,
          },
        ],
        form.reason,
      )
      ElMessage.success('订单变更已提交')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '操作失败')
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

onMounted(load)
</script>

<template>
  <section v-loading="loading" class="order-detail">
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <template v-if="order">
      <header class="head">
        <div>
          <h1>{{ order.orderNo }}</h1>
          <span class="meta">{{ order.origin }} · {{ order.status }} · {{ order.orderDate }} → {{ order.expectedDeliveryDate }}</span>
        </div>
        <div class="actions">
          <el-button v-if="auth.hasPermission('finance.cash.receive')" @click="openPayment('payment')">收款</el-button>
          <el-button v-if="auth.hasPermission('finance.cash.receive')" @click="openPayment('refund')">退款</el-button>
          <el-button v-if="auth.hasPermission('order.change')" @click="openPayment('change')">变更价格</el-button>
          <el-button v-if="auth.hasPermission('order.complete')" type="primary" @click="complete">完成订单</el-button>
        </div>
      </header>

      <el-descriptions :column="3" border>
        <el-descriptions-item label="当前应收">{{ order.currentReceivableAmount.amount }}</el-descriptions-item>
        <el-descriptions-item label="已收">{{ order.fulfillment.receivedAmount.amount }}</el-descriptions-item>
        <el-descriptions-item label="已退">{{ order.fulfillment.refundedAmount.amount }}</el-descriptions-item>
        <el-descriptions-item label="交付履约">{{ order.deliveryFulfilled ? '已满足' : '未满足' }}</el-descriptions-item>
        <el-descriptions-item label="付款履约">{{ order.paymentFulfilled ? '已满足' : '未满足' }}</el-descriptions-item>
        <el-descriptions-item label="阻断原因">
          {{ order.fulfillment.blockingReasons.join('；') || '无' }}
        </el-descriptions-item>
      </el-descriptions>

      <h2>订单项</h2>
      <el-table :data="order.items" border>
        <el-table-column prop="orderItemBusinessId" label="订单项" min-width="180" />
        <el-table-column prop="activeQuantity" label="有效数量" width="100" />
        <el-table-column label="目标工序" width="120">
          <template #default="{ row }">{{ row.agreement.targetProcess }}</template>
        </el-table-column>
        <el-table-column label="单价" width="120">
          <template #default="{ row }">{{ row.agreement.unitPrice.amount }}</template>
        </el-table-column>
        <el-table-column label="已交付" width="90">
          <template #default="{ row }">{{ row.agreement.fulfilledQuantity }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button
              v-if="auth.hasPermission('order.ship')"
              link
              type="primary"
              @click="openShipment(row.orderItemBusinessId)"
            >
              发货
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button class="back" @click="router.push('/orders')">返回列表</el-button>
    </template>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="440px">
      <el-form label-width="92px">
        <template v-if="dialog === 'payment' || dialog === 'refund'">
          <el-form-item label="金额"><el-input v-model="form.amount" /></el-form-item>
          <el-form-item label="业务日期">
            <el-date-picker v-model="form.businessDate" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
          <el-form-item label="凭证">
            <BusinessFileUpload
              v-model="voucherId"
              permission-code="finance.read"
              label="上传凭证（可选）"
            />
          </el-form-item>
        </template>
        <template v-if="dialog === 'shipment'">
          <el-form-item label="交付约定"><el-input v-model="form.groupId" disabled /></el-form-item>
          <el-form-item label="生产来源"><el-input v-model="form.sourceId" /></el-form-item>
          <el-form-item label="数量"><el-input v-model.number="form.quantity" type="number" /></el-form-item>
          <el-form-item label="物流单号"><el-input v-model="form.trackingNo" /></el-form-item>
          <el-form-item label="发货日期">
            <el-date-picker v-model="form.businessDate" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
        </template>
        <template v-if="dialog === 'change'">
          <el-form-item label="新单价"><el-input v-model="form.unitPrice" /></el-form-item>
          <el-form-item label="变更原因"><el-input v-model="form.reason" type="textarea" :rows="2" /></el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">确认</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.order-detail { background: #fff; border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 20px; }
.head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
h1 { margin: 0 0 4px; font-size: 20px; }
.meta { color: var(--el-text-color-secondary); font-size: 12px; }
.actions { display: flex; gap: 8px; flex-wrap: wrap; }
h2 { margin: 20px 0 10px; font-size: 16px; }
.back { margin-top: 12px; }
.alert { margin-bottom: 12px; }
</style>
