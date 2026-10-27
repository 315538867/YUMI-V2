<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useListQuery } from '@/platform/composables/useListQuery'
import type { ProductView } from '@/modules/product/contracts'
import { productService } from '@/modules/product/services/productService'
import type { ProductionProjectionRow } from '@/modules/production/contracts'
import { productionService } from '@/modules/production/services/productionService'
import ListPage from '@/shared/components/ListPage.vue'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'

const { loading, errorText, run } = useAsyncLoad()

const products = ref<ProductView[]>([])
const rows = ref<ProductionProjectionRow[]>([])
const { filters, search } = useListQuery(
  { productId: '' },
  { paged: false, onSearch: () => void load() },
)

async function loadProducts(): Promise<void> {
  await run(async () => {
    products.value = await productService.list('', 'ACTIVE', 1, 100)
    if (!products.value.some((item) => item.productBusinessId === filters.productId)) {
      filters.productId = products.value[0]?.productBusinessId ?? ''
    }
    if (filters.productId !== '') {
      await load()
    }
  }, '商品加载失败')
}

async function load(): Promise<void> {
  if (filters.productId === '') {
    rows.value = []
    return
  }
  await run(async () => {
    rows.value = await productionService.demands(filters.productId)
  }, '需求缺口加载失败')
}

onMounted(loadProducts)
</script>

<template>
  <ListPage
    class="gap-page"
    title="需求缺口"
    :loading="loading"
    :error="errorText"
    :empty="!loading && errorText === '' && rows.length === 0"
    empty-text="该商品暂无生产需求"
    @retry="load"
  >
    <template #filters>
      <el-select
        v-model="filters.productId"
        filterable
        class="product"
        aria-label="商品"
        @change="search"
      >
        <el-option
          v-for="product in products"
          :key="product.productBusinessId"
          :label="product.name"
          :value="product.productBusinessId"
        />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
    </template>
    <el-table :data="rows" empty-text="暂无需求" border>
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
    <template #footer>
      <p class="hint">报废与转线下分列，不相加为“总损失”；四个物理分量相互独立。</p>
    </template>
  </ListPage>
</template>

<style scoped>
.product {
  width: 260px;
}

.hint {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
