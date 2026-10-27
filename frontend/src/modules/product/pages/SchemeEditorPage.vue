<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { useUnsavedGuard } from '@/platform/composables/useUnsavedGuard'
import { money } from '@/platform/services/money'
import type {
  MaterialSource,
  ProcessCode,
  ProductView,
  SchemeBudgetView,
  SchemeInput,
  SchemeView,
} from '@/modules/product/contracts'
import { productService } from '@/modules/product/services/productService'
import MoneyText from '@/shared/components/MoneyText.vue'

const route = useRoute()
const router = useRouter()
const productId = String(route.params.productBusinessId ?? '')
const schemeId =
  typeof route.params.schemeBusinessId === 'string' ? route.params.schemeBusinessId : ''
const editing = schemeId !== ''
const product = ref<ProductView | null>(null)
const scheme = ref<SchemeView | null>(null)
const loading = ref(true)
const saving = ref(false)
const errorText = ref('')
const budgetPreview = ref<SchemeBudgetView | null>(null)
const previewLoading = ref(false)
const previewError = ref('')
let previewTimer: ReturnType<typeof setTimeout> | null = null
const initialSnapshot = ref('')
const isDirty = computed(
  () => loading.value === false && JSON.stringify(form) !== initialSnapshot.value,
)
const { allowLeave } = useUnsavedGuard(() => isDirty.value)

const form = reactive({
  name: '',
  targetProcess: 'SEAM_CUTTING' as ProcessCode,
  salePrice: '0.00',
  glueSource: 'STUDIO' as MaterialSource,
  colorPasteSource: 'STUDIO' as MaterialSource,
  makingOtherMaterialCost: '0.00',
  baggingBagUnitCost: '0.00',
  baggingBagSource: 'STUDIO' as MaterialSource,
  baggingDecorationUnitPrice: '0.00',
  baggingDecorationSource: 'STUDIO' as MaterialSource,
  baggingOtherCost: '0.00',
  seamCombinedMaterialCost: '0.00',
  seamOtherCost: '0.00',
})

async function load(): Promise<void> {
  loading.value = true
  try {
    product.value = await productService.get(productId)
    if (editing) {
      const schemes = await productService.schemes(productId)
      const current = schemes.find((item) => item.schemeBusinessId === schemeId)
      if (current === undefined) {
        throw new Error('方案不存在')
      }
      scheme.value = current
      Object.assign(form, {
        name: current.name,
        targetProcess: current.targetProcess,
        salePrice: current.salePrice.amount,
        glueSource: current.glueSource,
        colorPasteSource: current.colorPasteSource,
        makingOtherMaterialCost: current.making.otherMaterialCost.amount,
        baggingBagUnitCost: current.bagging.bagUnitCost.amount,
        baggingBagSource: current.bagging.bagSource,
        baggingDecorationUnitPrice: current.bagging.decorationUnitPrice.amount,
        baggingDecorationSource: current.bagging.decorationSource,
        baggingOtherCost: current.bagging.otherCost.amount,
        seamCombinedMaterialCost: current.seamCutting.combinedMaterialCost.amount,
        seamOtherCost: current.seamCutting.otherCost.amount,
      })
    } else {
      form.name = '自定义方案'
    }
    initialSnapshot.value = JSON.stringify(form)
  } catch (error) {
    errorText.value = userMessageOf(error, '方案加载失败')
  } finally {
    loading.value = false
  }
}

function input(): SchemeInput {
  return {
    name: form.name.trim(),
    targetProcess: form.targetProcess,
    salePrice: money(form.salePrice),
    glueSource: form.glueSource,
    colorPasteSource: form.colorPasteSource,
    makingOtherMaterialCost: money(form.makingOtherMaterialCost),
    baggingBagUnitCost: money(form.baggingBagUnitCost),
    baggingBagSource: form.baggingBagSource,
    baggingDecorationUnitPrice: money(form.baggingDecorationUnitPrice),
    baggingDecorationSource: form.baggingDecorationSource,
    baggingOtherCost: money(form.baggingOtherCost),
    seamCombinedMaterialCost: money(form.seamCombinedMaterialCost),
    seamOtherCost: money(form.seamOtherCost),
  }
}

function schedulePreview(): void {
  if (previewTimer !== null) {
    clearTimeout(previewTimer)
  }
  if (form.salePrice.trim() === '') {
    budgetPreview.value = null
    return
  }
  previewTimer = setTimeout(async () => {
    previewLoading.value = true
    previewError.value = ''
    try {
      budgetPreview.value = await productService.previewSchemeBudget(productId, input())
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
  if (product.value === null) {
    return
  }
  saving.value = true
  errorText.value = ''
  try {
    if (editing && scheme.value !== null) {
      await productService.updateScheme(
        schemeId,
        input(),
        product.value.contentVersion,
        scheme.value.rowVersion,
      )
    } else {
      await productService.createScheme(productId, input(), product.value.contentVersion)
    }
    ElMessage.success('方案已保存，预算由服务端重新计算')
    allowLeave()
    await router.replace(`/products/${productId}`)
  } catch (error) {
    errorText.value = userMessageOf(error, '方案保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <section v-loading="loading" class="scheme-editor">
    <header class="head">
      <h1>{{ editing ? '编辑自定义方案' : '新建自定义方案' }}</h1>
      <div>
        <el-button @click="router.back()">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存方案</el-button>
      </div>
    </header>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-alert
      type="info"
      :closable="false"
      title="人工、工时、提成与额外单件费用继承商品，本页不能覆盖；保存后由后端返回预算。"
      class="alert"
    />
    <div class="editor-grid">
      <el-form label-width="150px" class="form">
        <el-form-item label="方案名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="目标工序">
          <el-select v-model="form.targetProcess">
            <el-option label="制作" value="MAKING" />
            <el-option label="捏毛装袋" value="BAGGING" />
            <el-option label="缝边剪袋" value="SEAM_CUTTING" />
          </el-select>
        </el-form-item>
        <el-form-item label="销售价">
          <el-input v-model="form.salePrice" />
        </el-form-item>
        <el-form-item label="胶水供料方">
          <el-select v-model="form.glueSource">
            <el-option label="工作室" value="STUDIO" />
            <el-option label="客户" value="CUSTOMER" />
          </el-select>
        </el-form-item>
        <el-form-item label="色浆供料方">
          <el-select v-model="form.colorPasteSource">
            <el-option label="工作室" value="STUDIO" />
            <el-option label="客户" value="CUSTOMER" />
          </el-select>
        </el-form-item>
        <el-form-item label="制作其他材料">
          <el-input v-model="form.makingOtherMaterialCost" />
        </el-form-item>
        <el-form-item label="单件袋子成本">
          <el-input v-model="form.baggingBagUnitCost" />
        </el-form-item>
        <el-form-item label="袋子供料方">
          <el-select v-model="form.baggingBagSource">
            <el-option label="工作室" value="STUDIO" />
            <el-option label="客户" value="CUSTOMER" />
          </el-select>
        </el-form-item>
        <el-form-item label="单件装饰品">
          <el-input v-model="form.baggingDecorationUnitPrice" />
        </el-form-item>
        <el-form-item label="装饰品供料方">
          <el-select v-model="form.baggingDecorationSource">
            <el-option label="工作室" value="STUDIO" />
            <el-option label="客户" value="CUSTOMER" />
          </el-select>
        </el-form-item>
        <el-form-item label="捏毛装袋其他费用">
          <el-input v-model="form.baggingOtherCost" />
        </el-form-item>
        <el-form-item label="综合材料成本">
          <el-input v-model="form.seamCombinedMaterialCost" />
        </el-form-item>
        <el-form-item label="缝边剪袋其他费用">
          <el-input v-model="form.seamOtherCost" />
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
        <p v-else class="hint">填写目标工序和销售价后显示预算预览。</p>
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

.scheme-editor {
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

.form {
  max-width: 680px;
}

.alert {
  margin-bottom: 12px;
}
</style>
