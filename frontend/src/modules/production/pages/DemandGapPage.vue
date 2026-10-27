<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { userMessageOf } from '@/platform/services/errorMessage'
import type { ProductView } from '@/modules/product/contracts'
import { productService } from '@/modules/product/services/productService'
import type { ProductionProjectionRow } from '@/modules/production/contracts'
import { productionService } from '@/modules/production/services/productionService'

const products = ref<ProductView[]>([])
const productId = ref('')
const rows = ref<ProductionProjectionRow[]>([])
const loading = ref(false)
const errorText = ref('')

async function loadProducts(): Promise<void> {
  try {
    products.value = await productService.list('', 'ACTIVE')
    productId.value = products.value[0]?.productBusinessId ?? ''
    if (productId.value !== '') {
      await load()
    }
  } catch (error) {
    errorText.value = userMessageOf(error, '商品加载失败')
  }
}

async function load(): Promise<void> {
  if (productId.value === '') {
    return
  }
  loading.value = true
  try {
    rows.value = await productionService.demands(productId.value)
  } catch (error) {
    errorText.value = userMessageOf(error, '需求缺口加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(loadProducts)
</script>

<template>
  <section class="gap-page">
    <h1>需求缺口</h1>
    <div class="toolbar">
      <el-select v-model="productId" filterable class="product" aria-label="商品" @change="load">
        <el-option
          v-for="product in products"
          :key="product.productBusinessId"
          :label="product.name"
          :value="product.productBusinessId"
        />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
    </div>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-table v-loading="loading" :data="rows" empty-text="暂无需求" border>
      <el-table-column prop="demandBusinessId" label="需求" min-width="200" />
      <el-table-column prop="requiredQuantity" label="需求数量" width="100" />
      <el-table-column prop="pendingWorkQuantity" label="待制" width="90" />
      <el-table-column prop="inProcessQuantity" label="在制" width="90" />
      <el-table-column prop="fulfilledQuantity" label="合格完成" width="100" />
      <el-table-column prop="fulfillmentGapQuantity" label="缺口" width="90" />
      <el-table-column prop="destroyedQuantity" label="实际销毁" width="100" />
      <el-table-column prop="offlineInventoryQuantity" label="转线下" width="100" />
      <el-table-column prop="inSystemQuantity" label="系统内" width="90" />
      <el-table-column prop="consumedQuantity" label="已消费" width="90" />
    </el-table>
    <p class="hint">报废与转线下分列，不相加为“总损失”；四个物理分量相互独立。</p>
  </section>
</template>

<style scoped>
.gap-page {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 16px;
}

h1 {
  margin: 0 0 12px;
  font-size: 18px;
}

.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.product {
  width: 260px;
}

.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.alert {
  margin-bottom: 12px;
}
</style>
