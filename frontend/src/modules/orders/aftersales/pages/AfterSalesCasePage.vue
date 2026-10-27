<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { useListQuery } from '@/platform/composables/useListQuery'
import { codesOf, codeLabel } from '@/platform/display/codeCatalog'
import type { CodeOf } from '@/platform/display/codeCatalog'
import type {
  AfterSalesCaseView,
  AfterSalesRouteType,
  ObligationChangeRoute,
  ObligationType,
  PhysicalDestination,
  ReceivedReturn,
  ResponsibilityType,
  ReturnDispositionType,
} from '@/modules/orders/aftersales/contracts'
import { afterSalesService } from '@/modules/orders/aftersales/services/afterSalesService'
import { cashService } from '@/modules/finance/cash/services/cashService'
import CodeTag from '@/shared/components/CodeTag.vue'
import MoneyText from '@/shared/components/MoneyText.vue'
import PageState from '@/shared/components/PageState.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'

const current = ref<AfterSalesCaseView | null>(null)
const received = ref<ReceivedReturn[]>([])
const errorText = ref('')
const listLoading = ref(false)
const listError = ref('')
const detailLoading = ref(false)
const detailError = ref('')
const cases = ref<AfterSalesCaseView[]>([])
const { filters, sync } = useListQuery(
  { case: '', keyword: '', status: '' },
  { paged: false, onSearch: () => {} },
)
const costs = ref<
  Array<{
    sourceBusinessId: string
    costCategory: string
    amount: { amount: string }
    businessDate: string
    operationType: string
    reasonText: string | null
  }>
>([])
const successText = ref('')
const dialogVisible = ref(false)
const action = ref('open')
const costCategoryOptions = codesOf('finance.costCategory')
const form = reactive({
  orderBusinessId: '',
  issueSummary: '',
  routeType: 'REFUND_ONLY' as AfterSalesRouteType,
  obligationType: 'REFUND' as ObligationType,
  quantity: 1,
  amount: '',
  dueDate: new Date().toISOString().slice(0, 10),
  obligationId: '',
  receiptItemId: '',
  conditionCode: 'GOOD' as CodeOf<'afterSales.conditionCode'>,
  dispositionType: 'SCRAPPED' as ReturnDispositionType,
  destination: 'DESTROYED' as PhysicalDestination,
  responsibilityType: 'STUDIO' as ResponsibilityType,
  responsibleSubjectBusinessId: '',
  waivedReason: '',
  reason: '',
  changeRoute: 'REFUND' as ObligationChangeRoute,
  businessDate: new Date().toISOString().slice(0, 10),
  costCategory: 'AFTER_SALES' as CodeOf<'finance.costCategory'>,
  costSourceId: '',
  recipientName: '',
  recipientPhone: '',
  recipientAddress: '',
  trackingNo: '',
  offlineInventoryName: '',
})

onMounted(() => {
  if (filters.case !== '') {
    void load()
  }
  if (filters.keyword !== '' || filters.status !== '') {
    void searchCases()
  }
})

function openAction(kind: string, obligationId = '', receiptItemId = ''): void {
  action.value = kind
  form.obligationId = obligationId
  form.receiptItemId = receiptItemId
  form.quantity = 1
  form.amount = ''
  form.reason = ''
  form.waivedReason = ''
  form.responsibleSubjectBusinessId = ''
  form.conditionCode = 'GOOD'
  form.dispositionType = 'SCRAPPED'
  form.destination = 'DESTROYED'
  form.responsibilityType = 'STUDIO'
  form.offlineInventoryName = ''
  form.trackingNo = ''
  dialogVisible.value = true
}

async function searchCases(): Promise<void> {
  listLoading.value = true
  listError.value = ''
  try {
    cases.value = await afterSalesService.listCases(filters.keyword, filters.status)
  } catch (error) {
    listError.value = userMessageOf(error, '售后案例列表加载失败')
  } finally {
    listLoading.value = false
  }
}

async function applyListQuery(): Promise<void> {
  await sync()
  await searchCases()
}

async function resetList(): Promise<void> {
  filters.keyword = ''
  filters.status = ''
  await applyListQuery()
}

async function viewCase(caseBusinessId: string): Promise<void> {
  filters.case = caseBusinessId
  await applyCaseQuery()
}

async function applyCaseQuery(): Promise<void> {
  filters.case = filters.case.trim()
  await sync()
  await load()
}

async function load(): Promise<void> {
  if (filters.case === '') {
    current.value = null
    costs.value = []
    detailError.value = ''
    return
  }
  detailLoading.value = true
  detailError.value = ''
  try {
    current.value = await afterSalesService.getCase(filters.case)
    costs.value = await afterSalesService.listCosts(filters.case)
  } catch (error) {
    current.value = null
    detailError.value = userMessageOf(error, '售后案例加载失败')
  } finally {
    detailLoading.value = false
  }
}

async function submit(): Promise<void> {
  errorText.value = ''
  successText.value = ''
  try {
    if (action.value === 'open') {
      const obligation =
        form.obligationType === 'REFUND' || form.obligationType === 'COMPENSATION'
          ? {
              obligationType: form.obligationType,
              targetQuantity: null,
              targetAmount: { amount: form.amount, currency: 'CNY' },
              dueDate: form.dueDate,
              note: form.issueSummary,
            }
          : {
              obligationType: form.obligationType,
              targetQuantity: Number(form.quantity),
              targetAmount: null,
              dueDate: form.dueDate,
              note: form.issueSummary,
              orderItemBusinessId: null,
            }
      const created = await afterSalesService.openCase({
        orderBusinessId: form.orderBusinessId.trim(),
        issueSummary: form.issueSummary,
        routeType: form.routeType,
        obligations: [obligation],
      })
      filters.case = created.caseBusinessId
      successText.value = `售后案例已受理：${created.caseBusinessId}`
      await sync()
      await load()
    } else if (action.value === 'return') {
      const result = await afterSalesService.receiveReturn({
        obligationBusinessId: form.obligationId,
        quantity: Number(form.quantity),
        conditionCode: form.conditionCode,
      })
      received.value.push({
        receiptItemBusinessId: result.receiptItemBusinessId,
        obligationBusinessId: form.obligationId,
        quantity: Number(form.quantity),
        conditionCode: form.conditionCode,
      })
      successText.value = '退回已接收'
      await load()
    } else if (action.value === 'dispose') {
      await afterSalesService.disposeReturnedProduct({
        receiptItemBusinessId: form.receiptItemId,
        quantity: Number(form.quantity),
        dispositionType: form.dispositionType,
        physicalDestination: form.destination,
        businessDate: form.businessDate,
        reasonText: form.reason,
        responsibilityType: form.responsibilityType,
        responsibleSubjectBusinessId:
          form.responsibilityType === 'WAIVED' ? null : form.responsibleSubjectBusinessId,
        waivedReason: form.responsibilityType === 'WAIVED' ? form.waivedReason : null,
        offlineInventoryName:
          form.destination === 'TRANSFERRED_OFFLINE_INVENTORY' ? form.offlineInventoryName : null,
        offlineInventoryNote: null,
        handedOverBy: null,
        receivedBy: null,
        transferredAt:
          form.destination === 'TRANSFERRED_OFFLINE_INVENTORY'
            ? `${form.businessDate}T10:00:00`
            : null,
      })
      successText.value = '池内制品已处置；生产与售后指向同一次处置'
    } else if (action.value === 'return-customer') {
      await afterSalesService.returnToCustomer({
        receiptItemBusinessId: form.receiptItemId,
        quantity: Number(form.quantity),
        deliveredAt: `${form.businessDate}T10:00:00`,
        recipient: {
          customerName: form.recipientName,
          phone: form.recipientPhone,
          address: form.recipientAddress,
        },
        trackingNo: form.trackingNo,
      })
      successText.value = '原样退还已登记'
    } else if (action.value === 'repair') {
      await afterSalesService.requestRepair({
        obligationBusinessId: form.obligationId,
        receiptItemBusinessId: form.receiptItemId,
        quantity: Number(form.quantity),
      })
      successText.value = '返修路线已建立'
    } else if (action.value === 'change') {
      await afterSalesService.changeObligation(form.obligationId, {
        quantity: Number(form.quantity),
        route: form.changeRoute,
        amount:
          form.changeRoute === 'REFUND' || form.changeRoute === 'COMPENSATION'
            ? { amount: form.amount, currency: 'CNY' }
            : null,
        note: form.reason,
      })
      successText.value = '义务路线已变更'
      await load()
    } else if (action.value === 'cancel') {
      await afterSalesService.cancelObligation(form.obligationId, {
        poolDispositionType: form.dispositionType,
        poolPhysicalDestination: form.destination,
        responsibilityType: form.responsibilityType,
        responsibleSubjectBusinessId:
          form.responsibilityType === 'WAIVED' ? null : form.responsibleSubjectBusinessId,
        waivedReason: form.responsibilityType === 'WAIVED' ? form.waivedReason : null,
        offlineInventoryName: null,
        offlineInventoryNote: null,
        handedOverBy: null,
        receivedBy: null,
        transferredAt: null,
        businessDate: form.businessDate,
        reasonText: form.reason,
      })
      successText.value = '义务已取消'
      await load()
    } else if (action.value === 'pay') {
      const obligation = current.value?.obligations.find(
        (row) => row.obligationBusinessId === form.obligationId,
      )
      await cashService.refundAfterSales(
        form.obligationId,
        form.amount || obligation?.targetAmount?.amount || '0.00',
        form.businessDate,
      )
      successText.value = '赔付/退款已登记到现金'
    } else if (action.value === 'cost') {
      const cost = await afterSalesService.confirmCost({
        caseBusinessId: filters.case,
        costCategory: form.costCategory,
        amount: form.amount,
        businessDate: form.businessDate,
        reasonText: form.reason,
      })
      form.costSourceId = cost.sourceBusinessId
      successText.value = '售后费用已确认（不产生现金）'
      await load()
    } else if (action.value === 'cost-correct') {
      await afterSalesService.correctCost({
        caseBusinessId: filters.case,
        sourceBusinessId: form.costSourceId,
        amount: form.amount,
        businessDate: form.businessDate,
        reasonText: form.reason,
      })
      successText.value = '售后费用已更正'
      await load()
    } else if (action.value === 'complete') {
      await afterSalesService.complete(filters.case)
      successText.value = '售后案例已结案'
      await load()
    }
    dialogVisible.value = false
    ElMessage.success(successText.value)
  } catch (error) {
    errorText.value = userMessageOf(error, '售后操作失败')
  }
}
</script>

<template>
  <WorkspacePage class="aftersales-page">
    <div class="toolbar">
      <el-input
        v-model="filters.case"
        placeholder="售后案例标识"
        class="input"
        aria-label="售后案例标识"
        @keyup.enter="applyCaseQuery"
      />
      <el-button type="primary" @click="applyCaseQuery">查询</el-button>
      <el-button @click="openAction('open')">受理售后</el-button>
      <el-button v-if="current" type="success" @click="openAction('complete')">结案</el-button>
      <el-button v-if="current" @click="openAction('cost')">确认费用</el-button>
      <el-button v-if="current" @click="openAction('cost-correct')">更正费用</el-button>
    </div>
    <div class="toolbar">
      <el-input
        v-model="filters.keyword"
        placeholder="案例号或订单标识"
        class="input"
        aria-label="案例号或订单标识"
        @keyup.enter="applyListQuery"
      />
      <el-select
        v-model="filters.status"
        placeholder="全部状态"
        class="status"
        clearable
        aria-label="售后状态筛选"
      >
        <el-option label="受理中" value="OPEN" />
        <el-option label="处理中" value="PROCESSING" />
        <el-option label="已结案" value="COMPLETED" />
      </el-select>
      <el-button @click="applyListQuery">查询案例</el-button>
      <el-button @click="resetList">重置</el-button>
    </div>
    <PageState :loading="listLoading" :error="listError" @retry="searchCases">
      <el-table :data="cases" empty-text="没有匹配的售后案例" border size="small" class="case-list">
        <el-table-column prop="caseNo" label="案例号" width="150" />
        <el-table-column prop="orderBusinessId" label="订单" min-width="200" />
        <el-table-column prop="issueSummary" label="问题" min-width="180" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }"
            ><CodeTag category="afterSales.caseStatus" :code="row.status"
          /></template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" @click="viewCase(row.caseBusinessId)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
    </PageState>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-alert
      v-if="successText"
      type="success"
      :closable="false"
      :title="successText"
      class="alert"
    />

    <PageState :loading="detailLoading" :error="detailError" @retry="load">
      <template v-if="current">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="案例号">{{ current.caseNo }}</el-descriptions-item>
          <el-descriptions-item label="订单">{{ current.orderBusinessId }}</el-descriptions-item>
          <el-descriptions-item label="状态"
            ><CodeTag category="afterSales.caseStatus" :code="current.status"
          /></el-descriptions-item>
          <el-descriptions-item label="路线"
            ><CodeTag category="afterSales.routeType" :code="current.routeType"
          /></el-descriptions-item>
          <el-descriptions-item label="问题" :span="2">{{
            current.issueSummary
          }}</el-descriptions-item>
        </el-descriptions>

        <h2>义务</h2>
        <el-table :data="current.obligations" border>
          <el-table-column prop="obligationBusinessId" label="义务标识" min-width="200" />
          <el-table-column label="类型" width="170">
            <template #default="{ row }"
              ><CodeTag category="afterSales.obligationType" :code="row.obligationType"
            /></template>
          </el-table-column>
          <el-table-column label="目标" width="120">
            <template #default="{ row }">
              <span v-if="row.targetQuantity !== null">{{ row.targetQuantity }}</span
              ><MoneyText v-else :value="row.targetAmount" />
            </template>
          </el-table-column>
          <el-table-column label="状态" width="150">
            <template #default="{ row }"
              ><CodeTag category="afterSales.obligationStatus" :code="row.status"
            /></template>
          </el-table-column>
          <el-table-column label="操作" width="330" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openAction('pay', row.obligationBusinessId)"
                >赔付/退款</el-button
              >
              <el-button link type="primary" @click="openAction('return', row.obligationBusinessId)"
                >接收退回</el-button
              >
              <el-button link type="primary" @click="openAction('change', row.obligationBusinessId)"
                >变更路线</el-button
              >
              <el-button link type="danger" @click="openAction('cancel', row.obligationBusinessId)"
                >取消</el-button
              >
            </template>
          </el-table-column>
        </el-table>

        <h2>售后费用（不属于现金）</h2>
        <el-table :data="costs" empty-text="暂无售后费用" border size="small" class="case-list">
          <el-table-column prop="sourceBusinessId" label="费用来源" min-width="200" />
          <el-table-column label="分类" width="150">
            <template #default="{ row }"
              ><CodeTag category="finance.costCategory" :code="row.costCategory"
            /></template>
          </el-table-column>
          <el-table-column label="金额" width="130">
            <template #default="{ row }"><MoneyText :value="row.amount" /></template>
          </el-table-column>
          <el-table-column prop="businessDate" label="业务日期" width="120" />
          <el-table-column label="操作" width="110">
            <template #default="{ row }"
              ><CodeTag category="finance.costOperation" :code="row.operationType"
            /></template>
          </el-table-column>
          <el-table-column prop="reasonText" label="原因" min-width="160" />
        </el-table>

        <h2>本次已接收退回</h2>
        <el-table :data="received" empty-text="尚未通过本页接收退回" border size="small">
          <el-table-column prop="receiptItemBusinessId" label="接收明细" min-width="200" />
          <el-table-column prop="quantity" label="数量" width="80" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }"
              ><CodeTag category="afterSales.conditionCode" :code="row.conditionCode"
            /></template>
          </el-table-column>
          <el-table-column label="操作" width="240">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                @click="openAction('dispose', row.obligationBusinessId, row.receiptItemBusinessId)"
              >
                池内处置
              </el-button>
              <el-button
                link
                type="primary"
                @click="
                  openAction('return-customer', row.obligationBusinessId, row.receiptItemBusinessId)
                "
              >
                原样退还
              </el-button>
              <el-button
                link
                type="primary"
                @click="openAction('repair', row.obligationBusinessId, row.receiptItemBusinessId)"
              >
                返修
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </PageState>

    <el-dialog v-model="dialogVisible" :title="action" width="520px">
      <el-form label-width="110px">
        <template v-if="action === 'open'">
          <el-form-item label="订单标识"><el-input v-model="form.orderBusinessId" /></el-form-item>
          <el-form-item label="问题说明"
            ><el-input v-model="form.issueSummary" type="textarea" :rows="2"
          /></el-form-item>
          <el-form-item label="处理路线">
            <el-select v-model="form.routeType">
              <el-option label="仅退款" value="REFUND_ONLY" />
              <el-option label="仅赔付" value="COMPENSATION_ONLY" />
              <el-option label="退回返修" value="RETURN_AND_REWORK" />
              <el-option label="直接补发" value="DIRECT_REPLACEMENT" />
              <el-option label="混合" value="MIXED" />
            </el-select>
          </el-form-item>
          <el-form-item label="义务类型">
            <el-select v-model="form.obligationType">
              <el-option label="退款" value="REFUND" />
              <el-option label="赔付" value="COMPENSATION" />
              <el-option label="退回返修" value="RETURN_AND_REPAIR" />
              <el-option label="补发" value="REPLACEMENT" />
              <el-option label="客户配合" value="CUSTOMER_COOPERATION" />
            </el-select>
          </el-form-item>
          <el-form-item
            v-if="form.obligationType === 'REFUND' || form.obligationType === 'COMPENSATION'"
            label="目标金额"
          >
            <el-input v-model="form.amount" />
          </el-form-item>
          <el-form-item v-else label="目标数量"
            ><el-input v-model.number="form.quantity" type="number"
          /></el-form-item>
        </template>
        <template v-if="action === 'pay'">
          <el-form-item label="金额"
            ><el-input v-model="form.amount" placeholder="留空使用义务目标金额"
          /></el-form-item>
          <el-form-item label="业务日期"
            ><el-date-picker v-model="form.businessDate" type="date" value-format="YYYY-MM-DD"
          /></el-form-item>
        </template>
        <template v-if="action === 'return'">
          <el-form-item label="数量"
            ><el-input v-model.number="form.quantity" type="number"
          /></el-form-item>
          <el-form-item label="状态">
            <el-select v-model="form.conditionCode">
              <el-option label="良好" value="GOOD" />
              <el-option label="损坏" value="DAMAGED" />
              <el-option label="有缺陷" value="DEFECTIVE" />
            </el-select>
          </el-form-item>
        </template>
        <template v-if="action === 'dispose'">
          <el-form-item label="数量"
            ><el-input v-model.number="form.quantity" type="number"
          /></el-form-item>
          <el-form-item label="处置">
            <el-select v-model="form.dispositionType">
              <el-option label="需返修" value="REPAIR_REQUIRED" />
              <el-option label="报废" value="SCRAPPED" />
            </el-select>
          </el-form-item>
          <el-form-item label="去向">
            <el-select v-model="form.destination">
              <el-option label="实际销毁" value="DESTROYED" />
              <el-option label="转线下库存" value="TRANSFERRED_OFFLINE_INVENTORY" />
            </el-select>
          </el-form-item>
          <el-form-item label="线下名称"
            ><el-input v-model="form.offlineInventoryName"
          /></el-form-item>
          <el-form-item label="责任类型">
            <el-select v-model="form.responsibilityType">
              <el-option label="工作室" value="STUDIO" />
              <el-option label="员工" value="EMPLOYEE" />
              <el-option label="小组" value="TEAM" />
              <el-option label="客户" value="CUSTOMER" />
              <el-option label="免除" value="WAIVED" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="form.responsibilityType === 'WAIVED'" label="免除原因">
            <el-input v-model="form.waivedReason" />
          </el-form-item>
          <el-form-item v-else label="责任主体">
            <el-input v-model="form.responsibleSubjectBusinessId" />
          </el-form-item>
          <el-form-item label="原因"><el-input v-model="form.reason" /></el-form-item>
        </template>
        <template v-if="action === 'return-customer'">
          <el-form-item label="数量"
            ><el-input v-model.number="form.quantity" type="number"
          /></el-form-item>
          <el-form-item label="收货人"><el-input v-model="form.recipientName" /></el-form-item>
          <el-form-item label="电话"><el-input v-model="form.recipientPhone" /></el-form-item>
          <el-form-item label="地址"><el-input v-model="form.recipientAddress" /></el-form-item>
          <el-form-item label="物流单号"><el-input v-model="form.trackingNo" /></el-form-item>
        </template>
        <template v-if="action === 'repair'">
          <el-form-item label="接收明细"><el-input v-model="form.receiptItemId" /></el-form-item>
          <el-form-item label="数量"
            ><el-input v-model.number="form.quantity" type="number"
          /></el-form-item>
        </template>
        <template v-if="action === 'change'">
          <el-form-item label="数量"
            ><el-input v-model.number="form.quantity" type="number"
          /></el-form-item>
          <el-form-item label="新路线">
            <el-select v-model="form.changeRoute">
              <el-option label="退款" value="REFUND" />
              <el-option label="赔付" value="COMPENSATION" />
              <el-option label="返修" value="REPAIR" />
              <el-option label="补发" value="REPLACEMENT" />
              <el-option label="客户配合" value="CUSTOMER_COOPERATION" />
            </el-select>
          </el-form-item>
          <el-form-item
            v-if="form.changeRoute === 'REFUND' || form.changeRoute === 'COMPENSATION'"
            label="金额"
          >
            <el-input v-model="form.amount" />
          </el-form-item>
          <el-form-item label="说明"><el-input v-model="form.reason" /></el-form-item>
        </template>
        <template v-if="action === 'cancel'">
          <el-form-item label="池内处置">
            <el-select v-model="form.dispositionType">
              <el-option label="需返修" value="REPAIR_REQUIRED" />
              <el-option label="报废" value="SCRAPPED" />
            </el-select>
          </el-form-item>
          <el-form-item label="去向">
            <el-select v-model="form.destination">
              <el-option label="实际销毁" value="DESTROYED" />
              <el-option label="转线下库存" value="TRANSFERRED_OFFLINE_INVENTORY" />
            </el-select>
          </el-form-item>
          <el-form-item label="原因"><el-input v-model="form.reason" /></el-form-item>
        </template>
        <template v-if="action === 'cost' || action === 'cost-correct'">
          <el-form-item v-if="action === 'cost-correct'" label="费用来源">
            <el-input v-model="form.costSourceId" />
          </el-form-item>
          <el-form-item label="费用分类">
            <el-select v-model="form.costCategory">
              <el-option
                v-for="code in costCategoryOptions"
                :key="code"
                :value="code"
                :label="codeLabel('finance.costCategory', code)"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="金额"><el-input v-model="form.amount" /></el-form-item>
          <el-form-item label="业务日期"
            ><el-date-picker v-model="form.businessDate" type="date" value-format="YYYY-MM-DD"
          /></el-form-item>
          <el-form-item label="原因"
            ><el-input v-model="form.reason" type="textarea" :rows="2"
          /></el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">确认</el-button>
      </template>
    </el-dialog>
  </WorkspacePage>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.input {
  width: 260px;
}
.status {
  width: 140px;
}
.case-list {
  margin-bottom: 12px;
}
h2 {
  margin: 22px 0 10px;
  font-size: 16px;
}
.alert {
  margin-bottom: 12px;
}
</style>
