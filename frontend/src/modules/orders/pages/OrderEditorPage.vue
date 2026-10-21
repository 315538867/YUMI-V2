<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { money } from '@/platform/services/money'
import type { CustomerView } from '@/modules/customer/contracts'
import { customerService } from '@/modules/customer/services/customerService'
import type { ProductView, SchemeView } from '@/modules/product/contracts'
import { productService } from '@/modules/product/services/productService'
import type { ProcessCode } from '@/modules/orders/contracts'
import { orderService } from '@/modules/orders/services/orderService'

const router = useRouter()
const customers = ref<CustomerView[]>([])
const products = ref<ProductView[]>([])
const schemes = ref<SchemeView[]>([])
const loading = ref(true)
const saving = ref(false)
const errorText = ref('')
const form = reactive({
  customerBusinessId: '',
  productBusinessId: '',
  schemeBusinessId: '',
  targetProcess: 'SEAM_CUTTING' as ProcessCode,
  quantity: 1,
  unitPrice: '0.00',
  orderDate: new Date().toISOString().slice(0, 10),
  expectedDeliveryDate: new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 10),
  recipientName: '',
  recipientPhone: '',
  recipientAddress: '',
  glue: 'STUDIO',
  colorPaste: 'STUDIO',
  bag: 'STUDIO',
  decoration: 'STUDIO',
})

async function load(): Promise<void> {
  loading.value = true
  try {
    ;[customers.value, products.value] = await Promise.all([
      customerService.list('', 'ACTIVE'),
      productService.list('', 'ACTIVE'),
    ])
    form.customerBusinessId = customers.value[0]?.customerBusinessId ?? ''
    form.productBusinessId = products.value[0]?.productBusinessId ?? ''
    form.recipientName = customers.value[0]?.contactName ?? ''
    form.recipientPhone = customers.value[0]?.phone ?? ''
    form.recipientAddress = customers.value[0]?.defaultAddress ?? ''
    if (form.productBusinessId !== '') {
      await loadSchemes()
    }
  } catch (error) {
    errorText.value = userMessageOf(error, '录入数据加载失败')
  } finally {
    loading.value = false
  }
}

async function loadSchemes(): Promise<void> {
  if (form.productBusinessId === '') {
    return
  }
  schemes.value = (await productService.schemes(form.productBusinessId)).filter(
    (scheme) => scheme.status === 'ACTIVE',
  )
  form.schemeBusinessId = schemes.value[0]?.schemeBusinessId ?? ''
  applyScheme()
}

function applyScheme(): void {
  const scheme = schemes.value.find((item) => item.schemeBusinessId === form.schemeBusinessId)
  if (scheme === undefined) {
    return
  }
  form.targetProcess = scheme.targetProcess
  form.unitPrice = scheme.salePrice.amount
}

watch(() => form.productBusinessId, loadSchemes)
watch(() => form.schemeBusinessId, applyScheme)
watch(() => form.customerBusinessId, () => {
  const customer = customers.value.find((item) => item.customerBusinessId === form.customerBusinessId)
  if (customer) {
    form.recipientName = customer.contactName
    form.recipientPhone = customer.phone
    form.recipientAddress = customer.defaultAddress
  }
})

async function save(): Promise<void> {
  if (form.customerBusinessId === '' || form.schemeBusinessId === '') {
    errorText.value = '请选择客户与方案'
    return
  }
  saving.value = true
  errorText.value = ''
  try {
    const entry = await productService.orderEntry(form.schemeBusinessId)
    const created = await orderService.create({
      customerBusinessId: form.customerBusinessId,
      contactOverride: null,
      orderDate: form.orderDate,
      expectedDeliveryDate: form.expectedDeliveryDate,
      items: [
        {
          productBusinessId: form.productBusinessId,
          schemeSnapshotRef: entry.ref,
          targetProcess: form.targetProcess,
          quantity: Number(form.quantity),
          unitPrice: money(form.unitPrice),
          materialSources: {
            glue: form.glue as 'STUDIO' | 'CUSTOMER',
            colorPaste: form.colorPaste as 'STUDIO' | 'CUSTOMER',
            bag: form.bag as 'STUDIO' | 'CUSTOMER',
            decoration: form.decoration as 'STUDIO' | 'CUSTOMER',
          },
          recipient: {
            customerName: form.recipientName,
            phone: form.recipientPhone,
            address: form.recipientAddress,
          },
        },
      ],
    })
    ElMessage.success('订单已确认')
    await router.replace(`/orders/${created.orderBusinessId}`)
  } catch (error) {
    errorText.value = userMessageOf(error, '订单保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <section v-loading="loading" class="order-editor">
    <header class="head">
      <h1>新增订单</h1>
      <div>
        <el-button @click="router.back()">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">确认建单</el-button>
      </div>
    </header>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-form label-width="110px" class="form">
      <h2>1. 客户与日期</h2>
      <el-form-item label="客户">
        <el-select v-model="form.customerBusinessId" filterable>
          <el-option v-for="customer in customers" :key="customer.customerBusinessId" :label="customer.name" :value="customer.customerBusinessId" />
        </el-select>
      </el-form-item>
      <el-form-item label="订单日期">
        <el-date-picker v-model="form.orderDate" type="date" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-form-item label="预计交付">
        <el-date-picker v-model="form.expectedDeliveryDate" type="date" value-format="YYYY-MM-DD" />
      </el-form-item>

      <h2>2. 订单项</h2>
      <el-form-item label="商品">
        <el-select v-model="form.productBusinessId" filterable>
          <el-option v-for="product in products" :key="product.productBusinessId" :label="product.name" :value="product.productBusinessId" />
        </el-select>
      </el-form-item>
      <el-form-item label="方案">
        <el-select v-model="form.schemeBusinessId" filterable>
          <el-option v-for="scheme in schemes" :key="scheme.schemeBusinessId" :label="`${scheme.name}（${scheme.schemeType === 'SYSTEM_STANDARD' ? '标准' : '自定义'}）`" :value="scheme.schemeBusinessId" />
        </el-select>
      </el-form-item>
      <el-form-item label="目标工序">
        <el-select v-model="form.targetProcess">
          <el-option label="制作" value="MAKING" />
          <el-option label="捏毛装袋" value="BAGGING" />
          <el-option label="缝边剪袋" value="SEAM_CUTTING" />
        </el-select>
      </el-form-item>
      <el-form-item label="订购数量">
        <el-input v-model.number="form.quantity" type="number" />
      </el-form-item>
      <el-form-item label="单件成交价">
        <el-input v-model="form.unitPrice" />
      </el-form-item>

      <h2>3. 供料约定</h2>
      <el-form-item label="胶水来源">
        <el-select v-model="form.glue">
          <el-option label="工作室" value="STUDIO" />
          <el-option label="客户" value="CUSTOMER" />
        </el-select>
      </el-form-item>
      <el-form-item label="色浆来源">
        <el-select v-model="form.colorPaste">
          <el-option label="工作室" value="STUDIO" />
          <el-option label="客户" value="CUSTOMER" />
        </el-select>
      </el-form-item>
      <el-form-item label="袋子女来源">
        <el-select v-model="form.bag">
          <el-option label="工作室" value="STUDIO" />
          <el-option label="客户" value="CUSTOMER" />
        </el-select>
      </el-form-item>
      <el-form-item label="装饰品来源">
        <el-select v-model="form.decoration">
          <el-option label="工作室" value="STUDIO" />
          <el-option label="客户" value="CUSTOMER" />
        </el-select>
      </el-form-item>

      <h2>4. 收货信息</h2>
      <el-form-item label="收货人"><el-input v-model="form.recipientName" /></el-form-item>
      <el-form-item label="电话"><el-input v-model="form.recipientPhone" /></el-form-item>
      <el-form-item label="地址"><el-input v-model="form.recipientAddress" type="textarea" :rows="2" /></el-form-item>
    </el-form>
  </section>
</template>

<style scoped>
.order-editor { background: #fff; border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 20px; }
.head { display: flex; justify-content: space-between; align-items: center; }
h1 { margin: 0; font-size: 20px; }
h2 { margin: 20px 0 10px; font-size: 15px; }
.form { max-width: 680px; }
.alert { margin-top: 12px; }
</style>
