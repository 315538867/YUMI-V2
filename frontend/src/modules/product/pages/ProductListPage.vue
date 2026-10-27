<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { userMessageOf } from '@/platform/services/errorMessage'
import { useAuthStore } from '@/platform/stores/auth'
import type { ProductStatus, ProductView } from '@/modules/product/contracts'
import { productService } from '@/modules/product/services/productService'
import CodeTag from '@/shared/components/CodeTag.vue'

const router = useRouter()
const auth = useAuthStore()
const rows = ref<ProductView[]>([])
const loading = ref(false)
const errorText = ref('')
const filters = reactive<{ keyword: string; status: ProductStatus | '' }>({
  keyword: '',
  status: '',
})

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    rows.value = await productService.list(filters.keyword, filters.status)
  } catch (error) {
    errorText.value = userMessageOf(error, '商品列表加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="product-list">
    <h1>商品</h1>
    <div class="toolbar">
      <el-input
        v-model="filters.keyword"
        placeholder="商品名称或编号"
        clearable
        class="keyword"
        @keyup.enter="load"
      />
      <el-select v-model="filters.status" placeholder="全部状态" clearable class="status">
        <el-option label="启用" value="ACTIVE" />
        <el-option label="停用" value="INACTIVE" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button
        v-if="auth.hasPermission('product.create')"
        @click="router.push('/products/new')"
      >
        新增商品
      </el-button>
      <el-button @click="router.push('/products/entry-settings')">录入设置</el-button>
    </div>

    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />

    <el-table v-loading="loading" :data="rows" empty-text="暂无商品" border>
      <el-table-column prop="productNo" label="商品编号" width="140" />
      <el-table-column prop="name" label="商品名称" min-width="160" />
      <el-table-column prop="makingStarName" label="制作星级" width="110" />
      <el-table-column label="标准售价" width="140">
        <template #default="{ row }">
          {{ row.standardSalePrice.amount }} {{ row.standardSalePrice.currency }}
        </template>
      </el-table-column>
      <el-table-column prop="dailyCapacity" label="日产能" width="90" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <CodeTag category="common.status" :code="row.status" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="router.push(`/products/${row.productBusinessId}`)">
            查看
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.product-list h1 { margin: 0 0 12px; font-size: 18px; }

.product-list {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 16px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.keyword {
  width: 240px;
}

.status {
  width: 130px;
}

.alert {
  margin-bottom: 12px;
}
</style>
