<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { userMessageOf } from '@/platform/services/errorMessage'
import { useListQuery } from '@/platform/composables/useListQuery'
import type { ProductStatus, ProductView } from '@/modules/product/contracts'
import { productService } from '@/modules/product/services/productService'
import CodeTag from '@/shared/components/CodeTag.vue'
import ListPager from '@/shared/components/ListPager.vue'
import MoneyText from '@/shared/components/MoneyText.vue'
import PageState from '@/shared/components/PageState.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'

const router = useRouter()
const rows = ref<ProductView[]>([])
const loading = ref(false)
const errorText = ref('')
const { filters, page, size, sizes, search, reset, goPage, changeSize } = useListQuery(
  { keyword: '', status: '' as ProductStatus | '' },
  { onSearch: () => void load() },
)

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    rows.value = await productService.list(filters.keyword, filters.status, page.value, size.value)
  } catch (error) {
    errorText.value = userMessageOf(error, '商品列表加载失败')
  } finally {
    loading.value = false
  }
}

void load()
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
        @keyup.enter="search"
      />
      <el-select
        v-model="filters.status"
        placeholder="全部状态"
        clearable
        class="status"
        aria-label="商品状态筛选"
      >
        <el-option label="启用" value="ACTIVE" />
        <el-option label="停用" value="INACTIVE" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <PermissionButton permission="product.create" @click="router.push('/products/new')">
        新增商品
      </PermissionButton>
      <el-button @click="router.push('/products/entry-settings')">录入设置</el-button>
    </div>

    <PageState :loading="loading" :error="errorText" @retry="load">
      <el-table :data="rows" empty-text="没有匹配的商品" border>
        <el-table-column prop="productNo" label="商品编号" width="140" />
        <el-table-column prop="name" label="商品名称" min-width="160" />
        <el-table-column prop="makingStarName" label="制作星级" width="110" />
        <el-table-column label="标准售价" width="140">
          <template #default="{ row }">
            <MoneyText :value="row.standardSalePrice" />
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
            <el-button
              link
              type="primary"
              @click="router.push(`/products/${row.productBusinessId}`)"
            >
              查看
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <ListPager
        :page="page"
        :size="size"
        :count="rows.length"
        :sizes="sizes"
        @update:page="goPage"
        @update:size="changeSize"
      />
    </PageState>
  </section>
</template>

<style scoped>
.product-list h1 {
  margin: 0 0 12px;
  font-size: 18px;
}

.product-list {
  background: var(--yumi-surface);
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
