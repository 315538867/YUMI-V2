<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { useAuthStore } from '@/platform/stores/auth'
import type { ProductView, SchemeView } from '@/modules/product/contracts'
import { productService } from '@/modules/product/services/productService'
import CodeTag from '@/shared/components/CodeTag.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const productId = String(route.params.productBusinessId ?? '')
const product = ref<ProductView | null>(null)
const schemes = ref<SchemeView[]>([])
const loading = ref(false)
const errorText = ref('')
const canManage = computed(() => auth.hasPermission('product.update'))

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    ;[product.value, schemes.value] = await Promise.all([
      productService.get(productId),
      productService.schemes(productId),
    ])
  } catch (error) {
    errorText.value = userMessageOf(error, '商品详情加载失败')
  } finally {
    loading.value = false
  }
}

async function toggleStatus(): Promise<void> {
  if (product.value === null) {
    return
  }
  const target = product.value.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  try {
    product.value = await productService.changeStatus(
      productId,
      target,
      product.value.rowVersion,
    )
    ElMessage.success(target === 'ACTIVE' ? '商品已启用' : '商品已停用')
  } catch (error) {
    errorText.value = userMessageOf(error, '状态更新失败')
  }
}

async function deactivateScheme(scheme: SchemeView): Promise<void> {
  try {
    await ElMessageBox.confirm(`停用方案「${scheme.name}」？历史订单快照不受影响。`, '停用方案')
    await productService.deactivateScheme(scheme.schemeBusinessId, scheme.rowVersion)
    ElMessage.success('方案已停用')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      errorText.value = userMessageOf(error, '方案停用失败')
    }
  }
}

function percent(rate: number | null): string {
  return rate === null ? '—' : `${(rate * 100).toFixed(2)}%`
}

onMounted(load)
</script>

<template>
  <section v-loading="loading" class="product-detail">
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" />
    <template v-if="product">
      <header class="head">
        <div>
          <h1>{{ product.name }}</h1>
          <span class="meta">
            {{ product.productNo }} · 日产能 {{ product.dailyCapacity }} · 内容版本
            {{ product.contentVersion }}
          </span>
        </div>
        <div class="actions">
          <el-button v-if="canManage" @click="router.push(`/products/${productId}/edit`)">
            编辑商品
          </el-button>
          <el-button
            v-if="canManage"
            :type="product.status === 'ACTIVE' ? 'warning' : 'success'"
            @click="toggleStatus"
          >
            {{ product.status === 'ACTIVE' ? '停用商品' : '启用商品' }}
          </el-button>
        </div>
      </header>

      <el-descriptions :column="3" border>
        <el-descriptions-item label="标准售价">
          {{ product.standardSalePrice.amount }} {{ product.standardSalePrice.currency }}
        </el-descriptions-item>
        <el-descriptions-item label="标准重量">{{ product.standardWeightGrams }} g</el-descriptions-item>
        <el-descriptions-item label="星级">{{ product.makingStarName }}</el-descriptions-item>
        <el-descriptions-item label="胶水损耗率">{{ product.glueLossRate }}</el-descriptions-item>
        <el-descriptions-item label="胶水单价">
          {{ product.glueUnitPrice.amount }}
        </el-descriptions-item>
        <el-descriptions-item label="色浆单价">
          {{ product.colorPasteUnitPrice.amount }}
        </el-descriptions-item>
        <el-descriptions-item label="制作分钟">
          {{ product.makingStandardMinutes }}
        </el-descriptions-item>
        <el-descriptions-item label="制作提成">
          {{ product.makingCommission.amount }}
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <CodeTag category="common.status" :code="product.status" />
        </el-descriptions-item>
      </el-descriptions>

      <div class="section-head">
        <h2>方案与预算</h2>
        <el-button
          v-if="canManage"
          size="small"
          @click="router.push(`/products/${productId}/schemes/new`)"
        >
          新建自定义方案
        </el-button>
      </div>
      <el-table :data="schemes" border>
        <el-table-column prop="name" label="方案" min-width="150" />
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <CodeTag category="product.schemeType" :code="row.schemeType" />
          </template>
        </el-table-column>
        <el-table-column label="目标工序" width="120">
          <template #default="{ row }"><CodeTag category="product.targetProcess" :code="row.targetProcess" /></template>
        </el-table-column>
        <el-table-column label="售价" width="130">
          <template #default="{ row }">{{ row.salePrice.amount }}</template>
        </el-table-column>
        <el-table-column label="全资源成本" width="130">
          <template #default="{ row }">
            {{ row.latestBudget?.fullResourceUnitCost.amount ?? '—' }}
          </template>
        </el-table-column>
        <el-table-column label="工作室成本" width="130">
          <template #default="{ row }">
            {{ row.latestBudget?.studioBorneUnitCost.amount ?? '—' }}
          </template>
        </el-table-column>
        <el-table-column label="预计利润" width="130">
          <template #default="{ row }">
            {{ row.latestBudget?.expectedUnitProfit.amount ?? '—' }}
          </template>
        </el-table-column>
        <el-table-column label="利润率" width="100">
          <template #default="{ row }">
            {{ percent(row.latestBudget?.expectedProfitRate ?? null) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.schemeType === 'CUSTOM' && canManage"
              link
              type="primary"
              @click="router.push(`/products/${productId}/schemes/${row.schemeBusinessId}`)"
            >
              编辑
            </el-button>
            <el-button
              v-if="row.schemeType === 'CUSTOM'"
              link
              type="danger"
              @click="deactivateScheme(row)"
            >
              停用
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </section>
</template>

<style scoped>
.product-detail {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 20px;
}

.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}

h1 {
  margin: 0 0 4px;
  font-size: 20px;
}

.meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.actions {
  display: flex;
  gap: 8px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 24px 0 10px;
}

h2 {
  margin: 0;
  font-size: 16px;
}
</style>
