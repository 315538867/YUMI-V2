<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import { useEditorForm } from '@/platform/composables/useEditorForm'
import { useUnsavedGuard } from '@/platform/composables/useUnsavedGuard'
import FormErrorAlert from '@/shared/components/FormErrorAlert.vue'
import { money } from '@/platform/services/money'
import type { CustomerView } from '@/modules/customer/contracts'
import { customerService } from '@/modules/customer/services/customerService'
import type { ProductView, SchemeView } from '@/modules/product/contracts'
import { productService } from '@/modules/product/services/productService'
import { orderService } from '@/modules/orders/services/orderService'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'

const { loading, errorText, run } = useAsyncLoad(true)

const router = useRouter()
const customers = ref<CustomerView[]>([])
const products = ref<ProductView[]>([])
const schemes = ref<SchemeView[]>([])
const initialSnapshot = ref('')
const { run: runAction } = useAsyncAction()
const { saving, validationErrors, formAlert, fieldError, validate, submit } = useEditorForm(
  [
    'customerBusinessId',
    'productBusinessId',
    'schemeBusinessId',
    'quantity',
    'unitPrice',
    'recipientName',
    'recipientPhone',
    'recipientAddress',
  ],
  '订单已被其他操作修改，请重新打开后再提交',
)
const form = reactive({
  customerBusinessId: '',
  originalOrderReference: '',
  note: '',
  orderDate: new Date().toISOString().slice(0, 10),
  expectedDeliveryDate: new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 10),
  productBusinessId: '',
  schemeBusinessId: '',
  quantity: 1,
  unitPrice: '0.00',
  recipientName: '',
  recipientPhone: '',
  recipientAddress: '',
  stageCode: 'NOT_STARTED',
})
const isDirty = computed(
  () =>
    loading.value !== true &&
    initialSnapshot.value !== '' &&
    JSON.stringify(form) !== initialSnapshot.value,
)
const { allowLeave } = useUnsavedGuard(() => isDirty.value)

async function load(): Promise<void> {
  await run(async () => {
    ;[customers.value, products.value] = await Promise.all([
      customerService.list('', 'ACTIVE', 1, 100),
      productService.list('', 'ACTIVE', 1, 100),
    ])
    form.customerBusinessId = customers.value[0]?.customerBusinessId ?? ''
    form.productBusinessId = products.value[0]?.productBusinessId ?? ''
    if (form.productBusinessId !== '') {
      await loadSchemes()
    }
    const customer = customers.value[0]
    form.recipientName = customer?.contactName ?? ''
    form.recipientPhone = customer?.phone ?? ''
    form.recipientAddress = customer?.defaultAddress ?? ''
    initialSnapshot.value = JSON.stringify(form)
  }, '接管录入数据加载失败')
}

async function loadSchemes(): Promise<void> {
  schemes.value = (await productService.schemes(form.productBusinessId)).filter(
    (scheme) => scheme.status === 'ACTIVE',
  )
  form.schemeBusinessId = schemes.value[0]?.schemeBusinessId ?? ''
  form.unitPrice = schemes.value[0]?.salePrice.amount ?? '0.00'
}

function validateForm(): boolean {
  return validate([
    {
      field: 'customerBusinessId',
      invalid: form.customerBusinessId === '',
      message: '请选择客户',
    },
    {
      field: 'productBusinessId',
      invalid: form.productBusinessId === '',
      message: '请选择商品',
    },
    {
      field: 'schemeBusinessId',
      invalid: form.schemeBusinessId === '',
      message: '请选择方案',
    },
    {
      field: 'quantity',
      invalid: !Number.isFinite(Number(form.quantity)) || Number(form.quantity) <= 0,
      message: '接管数量必须大于 0',
    },
    {
      field: 'unitPrice',
      invalid: !/^\d+(\.\d{1,2})?$/.test(form.unitPrice) || Number(form.unitPrice) <= 0,
      message: '单件价格必须大于 0 且最多两位小数',
    },
    {
      field: 'recipientName',
      invalid: form.recipientName.trim() === '',
      message: '请填写收货人',
    },
    {
      field: 'recipientPhone',
      invalid: form.recipientPhone.trim() === '',
      message: '请填写电话',
    },
    {
      field: 'recipientAddress',
      invalid: form.recipientAddress.trim() === '',
      message: '请填写地址',
    },
  ])
}

async function save(): Promise<void> {
  if (!validateForm()) {
    return
  }
  const entry = await runAction(() => productService.orderEntry(form.schemeBusinessId), {
    fallback: '订单接管失败',
  })
  if (entry === undefined) {
    return
  }
  await submit(
    () =>
      orderService.intake({
        customerBusinessId: form.customerBusinessId,
        originalOrderReference: form.originalOrderReference,
        orderDate: form.orderDate,
        expectedDeliveryDate: form.expectedDeliveryDate,
        note: form.note,
        items: [
          {
            agreement: {
              productBusinessId: form.productBusinessId,
              schemeSnapshotRef: entry.ref,
              targetProcess: 'SEAM_CUTTING',
              quantity: Number(form.quantity),
              unitPrice: money(form.unitPrice),
              materialSources: {
                glue: 'STUDIO',
                colorPaste: 'STUDIO',
                bag: 'STUDIO',
                decoration: 'STUDIO',
              },
              recipient: {
                customerName: form.recipientName,
                phone: form.recipientPhone,
                address: form.recipientAddress,
              },
            },
            openingLines: [
              {
                stageCode: form.stageCode,
                physicalDestination: null,
                reworkProcessCode: null,
                quantity: Number(form.quantity),
                reasonText: null,
              },
            ],
          },
        ],
      }),
    {
      success: '已有订单已接管',
      fallback: '订单接管失败',
      onSuccess: async (created) => {
        allowLeave()
        await router.replace(`/orders/${created.orderBusinessId}`)
      },
    },
  )
}

onMounted(load)
</script>

<template>
  <WorkspacePage v-loading="loading" class="opening-page">
    <header class="head">
      <h1>已有订单接管</h1>
      <div>
        <el-button @click="router.back()">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">确认接管</el-button>
      </div>
    </header>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-alert
      type="info"
      :closable="false"
      title="接管只记录当前数量所在阶段，不构造历史任务、劳动或材料事实。"
      class="alert"
    />
    <el-form label-width="120px" class="form">
      <FormErrorAlert :error="formAlert" class="alert" />
      <el-form-item
        label="客户"
        :error="validationErrors.customerBusinessId || fieldError('customerBusinessId')"
      >
        <el-select v-model="form.customerBusinessId" filterable>
          <el-option
            v-for="customer in customers"
            :key="customer.customerBusinessId"
            :label="customer.name"
            :value="customer.customerBusinessId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="原订单参考"
        ><el-input v-model="form.originalOrderReference"
      /></el-form-item>
      <el-form-item label="订单日期"
        ><el-date-picker v-model="form.orderDate" type="date" value-format="YYYY-MM-DD"
      /></el-form-item>
      <el-form-item label="预计交付"
        ><el-date-picker v-model="form.expectedDeliveryDate" type="date" value-format="YYYY-MM-DD"
      /></el-form-item>
      <el-form-item
        label="商品"
        :error="validationErrors.productBusinessId || fieldError('productBusinessId')"
      >
        <el-select v-model="form.productBusinessId" filterable @change="loadSchemes">
          <el-option
            v-for="product in products"
            :key="product.productBusinessId"
            :label="product.name"
            :value="product.productBusinessId"
          />
        </el-select>
      </el-form-item>
      <el-form-item
        label="方案"
        :error="validationErrors.schemeBusinessId || fieldError('schemeBusinessId')"
      >
        <el-select v-model="form.schemeBusinessId" filterable>
          <el-option
            v-for="scheme in schemes"
            :key="scheme.schemeBusinessId"
            :label="scheme.name"
            :value="scheme.schemeBusinessId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="接管数量" :error="validationErrors.quantity || fieldError('quantity')">
        <el-input v-model.number="form.quantity" type="number" />
      </el-form-item>
      <el-form-item label="单件价格" :error="validationErrors.unitPrice || fieldError('unitPrice')">
        <el-input v-model="form.unitPrice" />
      </el-form-item>
      <el-form-item label="当前阶段">
        <el-select v-model="form.stageCode">
          <el-option label="未开始" value="NOT_STARTED" />
          <el-option label="制作中" value="MAKING" />
          <el-option label="捏毛装袋中" value="BAGGING" />
          <el-option label="缝边剪袋中" value="SEAM_CUTTING" />
        </el-select>
      </el-form-item>
      <el-form-item label="收货人" :error="validationErrors.recipientName">
        <el-input v-model="form.recipientName" />
      </el-form-item>
      <el-form-item label="电话" :error="validationErrors.recipientPhone">
        <el-input v-model="form.recipientPhone" />
      </el-form-item>
      <el-form-item label="地址" :error="validationErrors.recipientAddress">
        <el-input v-model="form.recipientAddress" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item label="接管说明"
        ><el-input v-model="form.note" type="textarea" :rows="2"
      /></el-form-item>
    </el-form>
  </WorkspacePage>
</template>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
h1 {
  margin: 0;
  font-size: 20px;
}
.form {
  max-width: 680px;
}
.alert {
  margin-top: 12px;
}
</style>
