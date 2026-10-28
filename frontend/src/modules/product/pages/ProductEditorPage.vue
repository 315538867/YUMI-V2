<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { userMessageOf } from '@/platform/services/errorMessage'
import { useEditorForm } from '@/platform/composables/useEditorForm'
import { useUnsavedGuard } from '@/platform/composables/useUnsavedGuard'
import { money } from '@/platform/services/money'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import type {
  EntryConfiguration,
  SchemeBudgetView,
  ProductDefinition,
  ProductView,
} from '@/modules/product/contracts'
import { productService } from '@/modules/product/services/productService'
import EditorPage from '@/shared/components/EditorPage.vue'
import FormErrorAlert from '@/shared/components/FormErrorAlert.vue'
import MoneyText from '@/shared/components/MoneyText.vue'

const { loading, errorText, run } = useAsyncLoad(true)

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
const { saving, validationErrors, formAlert, fieldError, validate, submit } = useEditorForm(
  ['name'],
  '商品已被其他操作修改，请重新打开后再保存',
)
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
  await run(async () => {
    configuration.value = await productService.entryConfiguration()
    const defaultStar = configuration.value.makingStars[0]
    if (defaultStar) {
      form.makingStarCode = defaultStar.starCode
      form.makingStandardMinutes = defaultStar.standardMinutes
    }
    const defaultBagging = configuration.value.processTimeOptions.find(
      (option) => option.processCode === 'BAGGING',
    )
    if (defaultBagging) {
      form.baggingStandardMinutes = defaultBagging.standardMinutes
    }
    const defaultSeam = configuration.value.processTimeOptions.find(
      (option) => option.processCode === 'SEAM_CUTTING',
    )
    if (defaultSeam) {
      form.seamStandardMinutes = defaultSeam.standardMinutes
    }
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
  }, '商品录入数据加载失败')
}

function syncMakingMinutes(starCode: string): void {
  const star = configuration.value?.makingStars.find((item) => item.starCode === starCode)
  if (star) {
    form.makingStandardMinutes = star.standardMinutes
  }
}

function processTimeOptions(processCode: string, current: number) {
  const options =
    configuration.value?.processTimeOptions.filter(
      (option) => option.processCode === processCode,
    ) ?? []
  if (current > 0 && !options.some((option) => option.standardMinutes === current)) {
    return [
      ...options,
      {
        optionBusinessId: `CURRENT-${processCode}`,
        processCode,
        optionCode: 'CURRENT',
        name: `当前商品 ${current} 分钟`,
        standardMinutes: current,
        sortOrder: Number.MAX_SAFE_INTEGER,
      },
    ]
  }
  return options
}

const baggingOptions = computed(() => processTimeOptions('BAGGING', form.baggingStandardMinutes))
const seamOptions = computed(() => processTimeOptions('SEAM_CUTTING', form.seamStandardMinutes))

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
  const valid = validate([
    { field: 'name', invalid: form.name.trim() === '', message: '请填写商品名称' },
  ])
  if (!valid) {
    return
  }
  const action =
    editing && original.value !== null
      ? () => productService.update(productId, definition(), original.value!.rowVersion)
      : () => productService.create(definition())
  await submit(action, {
    success: '商品已保存',
    fallback: '商品保存失败',
    onSuccess: async (saved) => {
      allowLeave()
      await router.replace(`/products/${saved.productBusinessId}`)
    },
  })
}

onMounted(load)
</script>

<template>
  <EditorPage
    :title="editing ? '编辑商品' : '新增商品'"
    preview-title="预算预览"
    :loading="loading"
    :error="errorText"
    @retry="load"
  >
    <template #actions>
      <el-button @click="router.back()">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存商品</el-button>
    </template>
    <el-form v-if="!loading" label-width="128px">
      <FormErrorAlert :error="formAlert" class="yumi-alert" />
      <h2>1. 基本信息</h2>
      <el-form-item label="商品名称" required :error="validationErrors.name || fieldError('name')">
        <el-input v-model="form.name" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="form.description" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item label="标准重量（克）">
        <el-input v-model="form.standardWeightGrams" />
      </el-form-item>
      <el-form-item label="制作星级">
        <el-select v-model="form.makingStarCode" @change="syncMakingMinutes">
          <el-option
            v-for="star in configuration?.makingStars ?? []"
            :key="star.starCode"
            :label="`${star.name} / ${star.standardMinutes} 分钟`"
            :value="star.starCode"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="制作标准分钟">
        <el-input v-model.number="form.makingStandardMinutes" type="number" disabled />
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
        <el-select v-model="form.baggingStandardMinutes">
          <el-option
            v-for="option in baggingOptions"
            :key="option.optionBusinessId"
            :label="option.name"
            :value="option.standardMinutes"
          />
        </el-select>
      </el-form-item>
      <el-form-item v-for="field in baggingFields" :key="field.key" :label="field.label">
        <el-input v-model="form[field.key]" />
      </el-form-item>

      <h2>4. 缝边剪袋</h2>
      <el-form-item label="标准分钟">
        <el-select v-model="form.seamStandardMinutes">
          <el-option
            v-for="option in seamOptions"
            :key="option.optionBusinessId"
            :label="option.name"
            :value="option.standardMinutes"
          />
        </el-select>
      </el-form-item>
      <el-form-item v-for="field in seamFields" :key="field.key" :label="field.label">
        <el-input v-model="form[field.key]" />
      </el-form-item>

      <h2>5. 额外单件费用</h2>
      <el-form-item v-for="field in extraFields" :key="field.key" :label="field.label">
        <el-input v-model="form[field.key]" />
      </el-form-item>
    </el-form>

    <template #preview>
      <p v-if="previewLoading" class="yumi-hint">正在计算…</p>
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
          <dt>客户供料标准价值</dt>
          <dd><MoneyText :value="budgetPreview.customerSuppliedUnitValue" /></dd>
        </dl>
        <p class="yumi-hint">只读预览，保存后以服务端预算结果为准。</p>
      </template>
      <p v-else class="yumi-hint">填写商品名称和制作星级后显示预算预览。</p>
    </template>
  </EditorPage>
</template>
