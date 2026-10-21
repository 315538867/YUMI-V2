<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { money } from '@/platform/services/money'
import type {
  MaterialSource,
  ProcessCode,
  ProductView,
  SchemeInput,
  SchemeView,
} from '@/modules/product/contracts'
import { productService } from '@/modules/product/services/productService'

const route = useRoute()
const router = useRouter()
const productId = String(route.params.productBusinessId ?? '')
const schemeId = typeof route.params.schemeBusinessId === 'string'
  ? route.params.schemeBusinessId
  : ''
const editing = schemeId !== ''
const product = ref<ProductView | null>(null)
const scheme = ref<SchemeView | null>(null)
const loading = ref(true)
const saving = ref(false)
const errorText = ref('')

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
  </section>
</template>

<style scoped>
.scheme-editor {
  background: #fff;
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
