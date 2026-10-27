<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { money } from '@/platform/services/money'
import type { CustomerView } from '@/modules/customer/contracts'
import { customerService } from '@/modules/customer/services/customerService'
import type { ProductView, SchemeView } from '@/modules/product/contracts'
import { productService } from '@/modules/product/services/productService'
import { orderService } from '@/modules/orders/services/orderService'

const router = useRouter()
const customers = ref<CustomerView[]>([])
const products = ref<ProductView[]>([])
const schemes = ref<SchemeView[]>([])
const saving = ref(false)
const errorText = ref('')
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

async function load(): Promise<void> {
  try {
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
  } catch (error) {
    errorText.value = userMessageOf(error, '接管录入数据加载失败')
  }
}

async function loadSchemes(): Promise<void> {
  schemes.value = (await productService.schemes(form.productBusinessId)).filter(
    (scheme) => scheme.status === 'ACTIVE',
  )
  form.schemeBusinessId = schemes.value[0]?.schemeBusinessId ?? ''
  form.unitPrice = schemes.value[0]?.salePrice.amount ?? '0.00'
}

async function save(): Promise<void> {
  if (form.customerBusinessId === '' || form.schemeBusinessId === '') {
    errorText.value = '请选择客户与方案'
    return
  }
  saving.value = true
  errorText.value = ''
  try {
    const entry = await productService.orderEntry(form.schemeBusinessId)
    const created = await orderService.intake({
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
    })
    ElMessage.success('已有订单已接管')
    await router.replace(`/orders/${created.orderBusinessId}`)
  } catch (error) {
    errorText.value = userMessageOf(error, '订单接管失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="opening-page">
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
      <el-form-item label="客户">
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
      <el-form-item label="商品">
        <el-select v-model="form.productBusinessId" filterable @change="loadSchemes">
          <el-option
            v-for="product in products"
            :key="product.productBusinessId"
            :label="product.name"
            :value="product.productBusinessId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="方案">
        <el-select v-model="form.schemeBusinessId" filterable>
          <el-option
            v-for="scheme in schemes"
            :key="scheme.schemeBusinessId"
            :label="scheme.name"
            :value="scheme.schemeBusinessId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="接管数量"
        ><el-input v-model.number="form.quantity" type="number"
      /></el-form-item>
      <el-form-item label="单件价格"><el-input v-model="form.unitPrice" /></el-form-item>
      <el-form-item label="当前阶段">
        <el-select v-model="form.stageCode">
          <el-option label="未开始" value="NOT_STARTED" />
          <el-option label="制作中" value="MAKING" />
          <el-option label="捏毛装袋中" value="BAGGING" />
          <el-option label="缝边剪袋中" value="SEAM_CUTTING" />
        </el-select>
      </el-form-item>
      <el-form-item label="收货人"><el-input v-model="form.recipientName" /></el-form-item>
      <el-form-item label="电话"><el-input v-model="form.recipientPhone" /></el-form-item>
      <el-form-item label="地址"
        ><el-input v-model="form.recipientAddress" type="textarea" :rows="2"
      /></el-form-item>
      <el-form-item label="接管说明"
        ><el-input v-model="form.note" type="textarea" :rows="2"
      /></el-form-item>
    </el-form>
  </section>
</template>

<style scoped>
.opening-page {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 20px;
}
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
