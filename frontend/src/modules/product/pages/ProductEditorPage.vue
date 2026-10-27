<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { useUnsavedGuard } from '@/platform/composables/useUnsavedGuard'
import { money } from '@/platform/services/money'
import type {
  EntryConfiguration,
  SchemeBudgetView,
  ProductDefinition,
  ProductView,
} from '@/modules/product/contracts'
import { productService } from '@/modules/product/services/productService'
import MoneyText from '@/shared/components/MoneyText.vue'

type MoneyKey =
  | 'standardSalePrice'
  | 'glueUnitPrice'
  | 'colorPasteUnitPrice'
  | 'makingCommission'
  | 'makingOtherMaterialCost'
  | 'baggingLaborCost'
  | 'baggingBagUnitCost'
  | 'baggingDecorationUnitPrice'
  | 'baggingOtherCost'
  | 'seamLaborCost'
  | 'seamCombinedMaterialCost'
  | 'seamOtherCost'
  | 'extraOtherTaskLaborCost'
  | 'extraTransportPackagingCost'
  | 'extraDailyMiscCost'
  | 'extraRentUtilitiesCost'
  | 'extraMouldAmortizationCost'
  | 'extraOtherCost'

interface EditorForm {
  name: string
  description: string
  standardWeightGrams: string
  makingStarCode: string
  makingStandardMinutes: number
  glueLossRate: string
  mouldCount: number
  mouldDailyBatches: number
  baggingStandardMinutes: number
  seamStandardMinutes: number
  standardSalePrice: string
  glueUnitPrice: string
  colorPasteUnitPrice: string
  makingCommission: string
  makingOtherMaterialCost: string
  baggingLaborCost: string
  baggingBagUnitCost: string
  baggingDecorationUnitPrice: string
  baggingOtherCost: string
  seamLaborCost: string
  seamCombinedMaterialCost: string
  seamOtherCost: string
  extraOtherTaskLaborCost: string
  extraTransportPackagingCost: string
  extraDailyMiscCost: string
  extraRentUtilitiesCost: string
  extraMouldAmortizationCost: string
  extraOtherCost: string
}

const route = useRoute()
const router = useRouter()
const productId =
  typeof route.params.productBusinessId === 'string' ? route.params.productBusinessId : ''
const editing = productId !== ''
const loading = ref(true)
const saving = ref(false)
const errorText = ref('')
const configuration = ref<EntryConfiguration | null>(null)
const original = ref<ProductView | null>(null)
const budgetPreview = ref<SchemeBudgetView | null>(null)
const previewLoading = ref(false)
const previewError = ref('')
let previewTimer: ReturnType<typeof setTimeout> | null = null
const initialSnapshot = ref('')
const isDirty = computed(
  () => loading.value === false && JSON.stringify(form) !== initialSnapshot.value,
)
const { allowLeave } = useUnsavedGuard(() => isDirty.value)

const form = reactive<EditorForm>({
  name: '',
  description: '',
  standardWeightGrams: '0.0000',
  makingStarCode: '',
  makingStandardMinutes: 0,
  glueLossRate: '0.0500',
  mouldCount: 1,
  mouldDailyBatches: 1,
  baggingStandardMinutes: 0,
  seamStandardMinutes: 0,
  standardSalePrice: '0.00',
  glueUnitPrice: '0.1000',
  colorPasteUnitPrice: '0.2000',
  makingCommission: '0.00',
  makingOtherMaterialCost: '0.00',
  baggingLaborCost: '0.00',
  baggingBagUnitCost: '0.00',
  baggingDecorationUnitPrice: '0.00',
  baggingOtherCost: '0.00',
  seamLaborCost: '0.00',
  seamCombinedMaterialCost: '0.00',
  seamOtherCost: '0.00',
  extraOtherTaskLaborCost: '0.00',
  extraTransportPackagingCost: '0.00',
  extraDailyMiscCost: '0.00',
  extraRentUtilitiesCost: '0.00',
  extraMouldAmortizationCost: '0.00',
  extraOtherCost: '0.00',
})

const makingFields: Array<{ key: MoneyKey; label: string }> = [
  { key: 'makingCommission', label: '制作提成' },
  { key: 'makingOtherMaterialCost', label: '制作其他材料' },
]
const baggingFields: Array<{ key: MoneyKey; label: string }> = [
  { key: 'baggingLaborCost', label: '捏毛装袋人工' },
  { key: 'baggingBagUnitCost', label: '单件袋子成本' },
  { key: 'baggingDecorationUnitPrice', label: '单件装饰品' },
  { key: 'baggingOtherCost', label: '捏毛装袋其他费用' },
]
const seamFields: Array<{ key: MoneyKey; label: string }> = [
  { key: 'seamLaborCost', label: '缝边剪袋人工' },
  { key: 'seamCombinedMaterialCost', label: '综合材料成本' },
  { key: 'seamOtherCost', label: '缝边剪袋其他费用' },
]
const extraFields: Array<{ key: MoneyKey; label: string }> = [
  { key: 'extraOtherTaskLaborCost', label: '其他任务人工费' },
  { key: 'extraTransportPackagingCost', label: '运输包装费' },
  { key: 'extraDailyMiscCost', label: '日常杂费' },
  { key: 'extraRentUtilitiesCost', label: '房租水电费' },
  { key: 'extraMouldAmortizationCost', label: '模具单件摊销' },
  { key: 'extraOtherCost', label: '其他费用' },
]

async function load(): Promise<void> {
  loading.value = true
  try {
    configuration.value = await productService.entryConfiguration()
    form.makingStarCode = configuration.value.makingStars[0]?.starCode ?? form.makingStarCode
    form.glueLossRate = configuration.value.glueLossRate
    form.glueUnitPrice = configuration.value.glueUnitPrice.amount
    form.colorPasteUnitPrice = configuration.value.colorPasteUnitPrice.amount
    if (editing) {
      const product = await productService.get(productId)
      original.value = product
      Object.assign(form, {
        name: product.name,
        description: product.description,
        standardWeightGrams: product.standardWeightGrams,
        makingStarCode: product.makingStarCode,
        makingStandardMinutes: product.makingStandardMinutes,
        glueLossRate: product.glueLossRate,
        mouldCount: product.mouldCount,
        mouldDailyBatches: product.mouldDailyBatches,
        baggingStandardMinutes: product.bagging.standardMinutes,
        seamStandardMinutes: product.seamCutting.standardMinutes,
        standardSalePrice: product.standardSalePrice.amount,
        glueUnitPrice: product.glueUnitPrice.amount,
        colorPasteUnitPrice: product.colorPasteUnitPrice.amount,
        makingCommission: product.makingCommission.amount,
        makingOtherMaterialCost: product.making.otherMaterialCost.amount,
        baggingLaborCost: product.bagging.laborCost.amount,
        baggingBagUnitCost: product.bagging.bagUnitCost.amount,
        baggingDecorationUnitPrice: product.bagging.decorationUnitPrice.amount,
        baggingOtherCost: product.bagging.otherCost.amount,
        seamLaborCost: product.seamCutting.laborCost.amount,
        seamCombinedMaterialCost: product.seamCutting.combinedMaterialCost.amount,
        seamOtherCost: product.seamCutting.otherCost.amount,
        extraOtherTaskLaborCost: product.extraCosts.otherTaskLaborCost.amount,
        extraTransportPackagingCost: product.extraCosts.transportPackagingCost.amount,
        extraDailyMiscCost: product.extraCosts.dailyMiscCost.amount,
        extraRentUtilitiesCost: product.extraCosts.rentUtilitiesCost.amount,
        extraMouldAmortizationCost: product.extraCosts.mouldUnitAmortizationCost.amount,
        extraOtherCost: product.extraCosts.otherCost.amount,
      })
    }
    initialSnapshot.value = JSON.stringify(form)
  } catch (error) {
    errorText.value = userMessageOf(error, '商品录入数据加载失败')
  } finally {
    loading.value = false
  }
}

function definition(): ProductDefinition {
  return {
    name: form.name.trim(),
    description: form.description,
    standardWeightGrams: form.standardWeightGrams,
    makingStarCode: form.makingStarCode,
    makingStandardMinutes: Number(form.makingStandardMinutes),
    standardSalePrice: money(form.standardSalePrice),
    glueLossRate: form.glueLossRate,
    glueUnitPrice: money(form.glueUnitPrice),
    colorPasteUnitPrice: money(form.colorPasteUnitPrice),
    makingCommission: money(form.makingCommission),
    mouldCount: Number(form.mouldCount),
    mouldDailyBatches: Number(form.mouldDailyBatches),
    makingOtherMaterialCost: money(form.makingOtherMaterialCost),
    baggingStandardMinutes: Number(form.baggingStandardMinutes),
    baggingLaborCost: money(form.baggingLaborCost),
    baggingBagUnitCost: money(form.baggingBagUnitCost),
    baggingDecorationUnitPrice: money(form.baggingDecorationUnitPrice),
    baggingOtherCost: money(form.baggingOtherCost),
    seamStandardMinutes: Number(form.seamStandardMinutes),
    seamLaborCost: money(form.seamLaborCost),
    seamCombinedMaterialCost: money(form.seamCombinedMaterialCost),
    seamOtherCost: money(form.seamOtherCost),
    extraOtherTaskLaborCost: money(form.extraOtherTaskLaborCost),
    extraTransportPackagingCost: money(form.extraTransportPackagingCost),
    extraDailyMiscCost: money(form.extraDailyMiscCost),
    extraRentUtilitiesCost: money(form.extraRentUtilitiesCost),
    extraMouldAmortizationCost: money(form.extraMouldAmortizationCost),
    extraOtherCost: money(form.extraOtherCost),
  }
}

function schedulePreview(): void {
  if (previewTimer !== null) {
    clearTimeout(previewTimer)
  }
  if (form.name.trim() === '' || form.makingStarCode === '') {
    budgetPreview.value = null
    return
  }
  previewTimer = setTimeout(async () => {
    previewLoading.value = true
    previewError.value = ''
    try {
      budgetPreview.value = await productService.previewBudget(definition())
    } catch (error) {
      previewError.value = userMessageOf(error, '预算预览计算失败')
    } finally {
      previewLoading.value = false
    }
  }, 400)
}

watch(form, schedulePreview, { deep: true })

onBeforeUnmount(() => {
  if (previewTimer !== null) {
    clearTimeout(previewTimer)
  }
})

async function save(): Promise<void> {
  if (form.name.trim() === '') {
    errorText.value = '请填写商品名称'
    return
  }
  saving.value = true
  errorText.value = ''
  try {
    const saved =
      editing && original.value !== null
        ? await productService.update(productId, definition(), original.value.rowVersion)
        : await productService.create(definition())
    ElMessage.success('商品已保存')
    allowLeave()
    await router.replace(`/products/${saved.productBusinessId}`)
  } catch (error) {
    errorText.value = userMessageOf(error, '商品保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <section v-loading="loading" class="product-editor">
    <header class="head">
      <h1>{{ editing ? '编辑商品' : '新增商品' }}</h1>
      <div>
        <el-button @click="router.back()">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存商品</el-button>
      </div>
    </header>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />

    <div class="editor-grid">
      <el-form v-if="!loading" label-width="128px" class="form">
        <h2>1. 基本信息</h2>
        <el-form-item label="商品名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="标准重量（克）">
          <el-input v-model="form.standardWeightGrams" />
        </el-form-item>
        <el-form-item label="制作星级">
          <el-select v-model="form.makingStarCode">
            <el-option
              v-for="star in configuration?.makingStars ?? []"
              :key="star.starCode"
              :label="`${star.name} / ${star.standardMinutes} 分钟`"
              :value="star.starCode"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="制作标准分钟">
          <el-input v-model.number="form.makingStandardMinutes" type="number" />
        </el-form-item>
        <el-form-item label="标准售价">
          <el-input v-model="form.standardSalePrice" />
        </el-form-item>
        <el-form-item label="胶水损耗率">
          <el-input v-model="form.glueLossRate" />
        </el-form-item>
        <el-form-item label="胶水单价/克">
          <el-input v-model="form.glueUnitPrice" />
        </el-form-item>
        <el-form-item label="色浆单价/克">
          <el-input v-model="form.colorPasteUnitPrice" />
        </el-form-item>
        <el-form-item label="模具数量">
          <el-input v-model.number="form.mouldCount" type="number" />
        </el-form-item>
        <el-form-item label="每日批次">
          <el-input v-model.number="form.mouldDailyBatches" type="number" />
        </el-form-item>

        <h2>2. 制作</h2>
        <el-form-item v-for="field in makingFields" :key="field.key" :label="field.label">
          <el-input v-model="form[field.key]" />
        </el-form-item>

        <h2>3. 捏毛装袋</h2>
        <el-form-item label="标准分钟">
          <el-input v-model.number="form.baggingStandardMinutes" type="number" />
        </el-form-item>
        <el-form-item v-for="field in baggingFields" :key="field.key" :label="field.label">
          <el-input v-model="form[field.key]" />
        </el-form-item>

        <h2>4. 缝边剪袋</h2>
        <el-form-item label="标准分钟">
          <el-input v-model.number="form.seamStandardMinutes" type="number" />
        </el-form-item>
        <el-form-item v-for="field in seamFields" :key="field.key" :label="field.label">
          <el-input v-model="form[field.key]" />
        </el-form-item>

        <h2>5. 额外单件费用</h2>
        <el-form-item v-for="field in extraFields" :key="field.key" :label="field.label">
          <el-input v-model="form[field.key]" />
        </el-form-item>
      </el-form>
      <aside class="budget-preview">
        <h2>预算预览</h2>
        <p v-if="previewLoading" class="hint">正在计算…</p>
        <el-alert v-else-if="previewError" type="error" :closable="false" :title="previewError" />
        <template v-else-if="budgetPreview">
          <dl>
            <dt>全资源成本</dt>
            <dd><MoneyText :value="budgetPreview.fullResourceUnitCost" /></dd>
            <dt>工作室成本</dt>
            <dd><MoneyText :value="budgetPreview.studioBorneUnitCost" /></dd>
            <dt>预计利润</dt>
            <dd><MoneyText :value="budgetPreview.expectedUnitProfit" /></dd>
            <dt>利润率</dt>
            <dd>
              {{
                budgetPreview.expectedProfitRate === null
                  ? '—'
                  : `${(budgetPreview.expectedProfitRate * 100).toFixed(2)}%`
              }}
            </dd>
          </dl>
          <p class="hint">只读预览，保存后以服务端预算结果为准。</p>
        </template>
        <p v-else class="hint">填写商品名称和制作星级后显示预算预览。</p>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.editor-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 16px;
  align-items: start;
}

.budget-preview {
  position: sticky;
  top: 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  padding: 12px 16px;
  background: var(--el-fill-color-lighter);
}

.budget-preview h2 {
  margin-top: 0;
}

.budget-preview dl {
  display: grid;
  grid-template-columns: 96px 1fr;
  gap: 8px 12px;
  margin: 0;
}

.budget-preview dt {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.budget-preview dd {
  margin: 0;
  font-size: 13px;
}

.product-editor {
  background: var(--yumi-surface);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 20px;
}

.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

h1 {
  margin: 0;
  font-size: 20px;
}

h2 {
  margin: 20px 0 10px;
  font-size: 16px;
  color: var(--el-text-color-primary);
}

.form {
  max-width: 720px;
}

.alert {
  margin-bottom: 12px;
}
</style>
