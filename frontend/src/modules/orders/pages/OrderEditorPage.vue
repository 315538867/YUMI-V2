<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { money } from '@/platform/services/money'
import type { CustomerView } from '@/modules/customer/contracts'
import { customerService } from '@/modules/customer/services/customerService'
import type { ProductView, SchemeView } from '@/modules/product/contracts'
import { productService } from '@/modules/product/services/productService'
import type {
  CreateOrderInput,
  MaterialSource,
  ProcessCode,
  ProductSnapshotRef,
} from '@/modules/orders/contracts'
import { orderService } from '@/modules/orders/services/orderService'
import CodeTag from '@/shared/components/CodeTag.vue'

interface DraftItem {
  key: string
  productBusinessId: string
  schemeBusinessId: string
  schemes: SchemeView[]
  targetProcess: ProcessCode
  quantity: number
  unitPrice: string
  glue: MaterialSource
  colorPaste: MaterialSource
  bag: MaterialSource
  decoration: MaterialSource
}

const router = useRouter()
const customers = ref<CustomerView[]>([])
const products = ref<ProductView[]>([])
const items = ref<DraftItem[]>([])
const loading = ref(true)
const saving = ref(false)
const errorText = ref('')
const previewVisible = ref(false)
const recipient = reactive({ name: '', phone: '', address: '' })
const form = reactive({
  customerBusinessId: '',
  orderDate: new Date().toISOString().slice(0, 10),
  expectedDeliveryDate: new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 10),
})

const totalQuantity = computed(() =>
  items.value.reduce((total, item) => total + (Number(item.quantity) || 0), 0),
)
const totalAmount = computed(() =>
  items.value.reduce(
    (total, item) => total + (Number(item.quantity) || 0) * (Number(item.unitPrice) || 0),
    0,
  ),
)

function nextKey(): string {
  return `item-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

function emptyItem(productBusinessId = ''): DraftItem {
  return {
    key: nextKey(),
    productBusinessId,
    schemeBusinessId: '',
    schemes: [],
    targetProcess: 'MAKING',
    quantity: 1,
    unitPrice: '0.00',
    glue: 'STUDIO',
    colorPaste: 'STUDIO',
    bag: 'STUDIO',
    decoration: 'STUDIO',
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    ;[customers.value, products.value] = await Promise.all([
      customerService.list('', 'ACTIVE', 1, 100),
      productService.list('', 'ACTIVE', 1, 100),
    ])
    form.customerBusinessId = customers.value[0]?.customerBusinessId ?? ''
    applyCustomer()
    const firstProduct = products.value[0]
    if (firstProduct) {
      const item = emptyItem(firstProduct.productBusinessId)
      await loadSchemes(item)
      items.value = [item]
    } else {
      items.value = [emptyItem()]
    }
  } catch (error) {
    errorText.value = userMessageOf(error, '录入数据加载失败')
  } finally {
    loading.value = false
  }
}

function applyCustomer(): void {
  const customer = customers.value.find((row) => row.customerBusinessId === form.customerBusinessId)
  if (customer) {
    recipient.name = customer.contactName
    recipient.phone = customer.phone
    recipient.address = customer.defaultAddress
  }
}

async function loadSchemes(item: DraftItem): Promise<void> {
  item.schemeBusinessId = ''
  item.schemes = []
  if (item.productBusinessId === '') {
    return
  }
  item.schemes = (await productService.schemes(item.productBusinessId)).filter(
    (scheme) => scheme.status === 'ACTIVE',
  )
  item.schemeBusinessId = item.schemes[0]?.schemeBusinessId ?? ''
  applyScheme(item)
}

function applyScheme(item: DraftItem): void {
  const scheme = item.schemes.find((row) => row.schemeBusinessId === item.schemeBusinessId)
  if (!scheme) {
    return
  }
  item.targetProcess = scheme.targetProcess
  item.unitPrice = scheme.salePrice.amount
  item.glue = scheme.glueSource
  item.colorPaste = scheme.colorPasteSource
  item.bag = scheme.bagging.bagSource
  item.decoration = scheme.bagging.decorationSource
}

async function addItem(): Promise<void> {
  const item = emptyItem(products.value[0]?.productBusinessId ?? '')
  if (item.productBusinessId !== '') {
    await loadSchemes(item)
  }
  items.value.push(item)
}

function removeItem(index: number): void {
  if (items.value.length === 1) {
    return
  }
  items.value.splice(index, 1)
}

function validate(): boolean {
  if (form.customerBusinessId === '') {
    errorText.value = '请选择客户'
    return false
  }
  if (items.value.length === 0) {
    errorText.value = '请至少录入一条商品明细'
    return false
  }
  for (const item of items.value) {
    if (item.productBusinessId === '' || item.schemeBusinessId === '') {
      errorText.value = '每条明细都必须选择商品与方案'
      return false
    }
    if (!Number.isFinite(Number(item.quantity)) || Number(item.quantity) <= 0) {
      errorText.value = '订购数量必须大于 0'
      return false
    }
    if (!/^\d+(\.\d{1,2})?$/.test(item.unitPrice) || Number(item.unitPrice) <= 0) {
      errorText.value = '单件成交价必须大于 0 且最多两位小数'
      return false
    }
  }
  if (
    recipient.name.trim() === '' ||
    recipient.phone.trim() === '' ||
    recipient.address.trim() === ''
  ) {
    errorText.value = '请填写收货人、电话和地址'
    return false
  }
  return true
}

function requestPreview(): void {
  if (!validate()) {
    return
  }
  errorText.value = ''
  previewVisible.value = true
}

async function confirmCreate(): Promise<void> {
  if (!validate()) {
    previewVisible.value = false
    return
  }
  saving.value = true
  try {
    const orderItems = await Promise.all(
      items.value.map(async (item) => {
        const entry = await productService.orderEntry(item.schemeBusinessId)
        return {
          productBusinessId: item.productBusinessId,
          schemeSnapshotRef: entry.ref as ProductSnapshotRef,
          targetProcess: item.targetProcess,
          quantity: Number(item.quantity),
          unitPrice: money(item.unitPrice),
          materialSources: {
            glue: item.glue,
            colorPaste: item.colorPaste,
            bag: item.bag,
            decoration: item.decoration,
          },
          recipient: {
            customerName: recipient.name,
            phone: recipient.phone,
            address: recipient.address,
          },
        }
      }),
    )
    const input: CreateOrderInput = {
      customerBusinessId: form.customerBusinessId,
      contactOverride: null,
      orderDate: form.orderDate,
      expectedDeliveryDate: form.expectedDeliveryDate,
      items: orderItems,
    }
    const created = await orderService.create(input)
    ElMessage.success('订单已确认')
    previewVisible.value = false
    await router.replace(`/orders/${created.orderBusinessId}`)
  } catch (error) {
    errorText.value = userMessageOf(error, '订单保存失败')
    previewVisible.value = false
  } finally {
    saving.value = false
  }
}

function productName(id: string): string {
  return products.value.find((row) => row.productBusinessId === id)?.name ?? id
}

function schemeName(item: DraftItem): string {
  return item.schemes.find((row) => row.schemeBusinessId === item.schemeBusinessId)?.name ?? '—'
}

onMounted(load)
</script>

<template>
  <section v-loading="loading" class="order-editor">
    <header class="head">
      <div>
        <h1>新增订单</h1>
        <span class="meta">填写本次成交约定，核对后一次确认</span>
      </div>
      <div>
        <el-button @click="router.back()">取消</el-button>
        <el-button type="primary" :loading="saving" @click="requestPreview">确认建单</el-button>
      </div>
    </header>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />

    <el-form label-width="110px" class="form">
      <h2>1. 客户与交付</h2>
      <el-form-item label="客户">
        <el-select v-model="form.customerBusinessId" filterable @change="applyCustomer">
          <el-option
            v-for="customer in customers"
            :key="customer.customerBusinessId"
            :label="customer.name"
            :value="customer.customerBusinessId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="订单日期">
        <el-date-picker v-model="form.orderDate" type="date" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-form-item label="预计交付">
        <el-date-picker v-model="form.expectedDeliveryDate" type="date" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-form-item label="收货人"><el-input v-model="recipient.name" /></el-form-item>
      <el-form-item label="电话"><el-input v-model="recipient.phone" /></el-form-item>
      <el-form-item label="地址">
        <el-input v-model="recipient.address" type="textarea" :rows="2" />
      </el-form-item>
      <p class="hint">本页所有明细共用上方收货信息。</p>

      <div class="section-head">
        <h2>2. 商品与成交约定</h2>
        <el-button @click="addItem">新增明细</el-button>
      </div>
      <div v-for="(item, index) in items" :key="item.key" class="item-card">
        <div class="item-head">
          <strong>明细 {{ index + 1 }}</strong>
          <el-button v-if="items.length > 1" link type="danger" @click="removeItem(index)">
            删除
          </el-button>
        </div>
        <el-form label-width="110px">
          <el-form-item label="商品">
            <el-select v-model="item.productBusinessId" filterable @change="loadSchemes(item)">
              <el-option
                v-for="product in products"
                :key="product.productBusinessId"
                :label="product.name"
                :value="product.productBusinessId"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="方案">
            <el-select v-model="item.schemeBusinessId" filterable @change="applyScheme(item)">
              <el-option
                v-for="scheme in item.schemes"
                :key="scheme.schemeBusinessId"
                :label="`${scheme.name}（${scheme.schemeType === 'SYSTEM_STANDARD' ? '标准' : '自定义'}）`"
                :value="scheme.schemeBusinessId"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="目标工序">
            <el-select v-model="item.targetProcess">
              <el-option label="制作" value="MAKING" />
              <el-option label="捏毛装袋" value="BAGGING" />
              <el-option label="缝边剪袋" value="SEAM_CUTTING" />
            </el-select>
          </el-form-item>
          <el-form-item label="订购数量">
            <el-input v-model.number="item.quantity" type="number" />
          </el-form-item>
          <el-form-item label="单件成交价">
            <el-input v-model="item.unitPrice" />
          </el-form-item>
          <el-form-item label="胶水来源">
            <el-select v-model="item.glue">
              <el-option label="工作室" value="STUDIO" />
              <el-option label="客户" value="CUSTOMER" />
            </el-select>
          </el-form-item>
          <el-form-item label="色浆来源">
            <el-select v-model="item.colorPaste">
              <el-option label="工作室" value="STUDIO" />
              <el-option label="客户" value="CUSTOMER" />
            </el-select>
          </el-form-item>
          <el-form-item label="袋子女来源">
            <el-select v-model="item.bag">
              <el-option label="工作室" value="STUDIO" />
              <el-option label="客户" value="CUSTOMER" />
            </el-select>
          </el-form-item>
          <el-form-item label="装饰品来源">
            <el-select v-model="item.decoration">
              <el-option label="工作室" value="STUDIO" />
              <el-option label="客户" value="CUSTOMER" />
            </el-select>
          </el-form-item>
        </el-form>
      </div>

      <h2>3. 本次订单</h2>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="明细数">{{ items.length }}</el-descriptions-item>
        <el-descriptions-item label="订购数量">{{ totalQuantity }}</el-descriptions-item>
        <el-descriptions-item label="金额合计"
          >{{ totalAmount.toFixed(2) }} CNY</el-descriptions-item
        >
      </el-descriptions>
    </el-form>

    <el-dialog v-model="previewVisible" title="确认本次订单" width="680px">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="客户">
          {{ customers.find((row) => row.customerBusinessId === form.customerBusinessId)?.name }}
        </el-descriptions-item>
        <el-descriptions-item label="订单 / 预计交付">
          {{ form.orderDate }} → {{ form.expectedDeliveryDate }}
        </el-descriptions-item>
        <el-descriptions-item label="收货人"
          >{{ recipient.name }} · {{ recipient.phone }}</el-descriptions-item
        >
        <el-descriptions-item label="地址">{{ recipient.address }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="items" border size="small" class="preview-table">
        <el-table-column label="商品" min-width="150">
          <template #default="{ row }">{{ productName(row.productBusinessId) }}</template>
        </el-table-column>
        <el-table-column label="方案" min-width="140">
          <template #default="{ row }">{{ schemeName(row) }}</template>
        </el-table-column>
        <el-table-column label="目标工序" width="120">
          <template #default="{ row }"
            ><CodeTag category="production.targetProcess" :code="row.targetProcess"
          /></template>
        </el-table-column>
        <el-table-column prop="quantity" label="数量" width="80" />
        <el-table-column prop="unitPrice" label="单价" width="100" />
      </el-table>
      <p class="preview-total">合计 {{ totalAmount.toFixed(2) }} CNY</p>
      <template #footer>
        <el-button @click="previewVisible = false">返回修改</el-button>
        <el-button type="primary" :loading="saving" @click="confirmCreate">确认建单</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.order-editor {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 20px;
}

.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 12px;
}

h1 {
  margin: 0 0 4px;
  font-size: 20px;
}

.meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

h2 {
  margin: 20px 0 10px;
  font-size: 16px;
}

.form {
  max-width: 860px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 20px;
}

.item-card {
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 12px 16px 4px;
  margin-bottom: 12px;
}

.item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin: 4px 0 0 110px;
}

.preview-table {
  margin-top: 12px;
}

.preview-total {
  text-align: right;
  font-weight: 600;
  margin-top: 10px;
}

.alert {
  margin-bottom: 12px;
}
</style>
