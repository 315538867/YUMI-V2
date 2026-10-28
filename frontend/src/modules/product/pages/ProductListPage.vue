<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useListQuery } from '@/platform/composables/useListQuery'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import type { ProductStatus, ProductView } from '@/modules/product/contracts'
import { productService } from '@/modules/product/services/productService'
import CodeTag from '@/shared/components/CodeTag.vue'
import ListPage from '@/shared/components/ListPage.vue'
import ListPager from '@/shared/components/ListPager.vue'
import MoneyText from '@/shared/components/MoneyText.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'

const { loading, errorText, run } = useAsyncLoad()

const router = useRouter()
const rows = ref<ProductView[]>([])
const { filters, page, size, sizes, search, reset, goPage, changeSize } = useListQuery(
  { keyword: '', status: '' as ProductStatus | '' },
  { onSearch: () => void load() },
)

async function load(): Promise<void> {
  await run(async () => {
    rows.value = await productService.list(filters.keyword, filters.status, page.value, size.value)
  }, '商品列表加载失败')
}

void load()
</script>

<template>
  <ListPage
    class="product-list"
    title="商品"
    hint="管理商品规格、工序参数与预算方案"
    panel-title="商品列表"
    panel-hint="商品是生产、订单和预算使用的主数据"
    :loading="loading"
    :error="errorText"
    @retry="load"
  >
    <template #filters>
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
    </template>
    <template #actions>
      <PermissionButton
        permission="product.create"
        type="primary"
        @click="router.push('/products/new')"
      >
        新增商品
      </PermissionButton>
      <PermissionButton
        permission="product.update"
        @click="router.push('/products/entry-settings')"
      >
        录入设置
      </PermissionButton>
    </template>
    <el-table :data="rows" empty-text="没有匹配的商品" border>
      <el-table-column prop="productNo" label="商品编号" width="140" />
      <el-table-column prop="name" label="商品名称" min-width="160" />
      <el-table-column prop="makingStarName" label="制作星级" width="110" />
      <el-table-column label="制作规格" width="160">
        <template #default="{ row }">
          <span class="yumi-cell-title">{{ row.standardWeightGrams }} g</span>
          <span class="yumi-cell-sub">{{ row.makingStandardMinutes }} 分钟 / 件</span>
        </template>
      </el-table-column>
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
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="router.push(`/products/${row.productBusinessId}`)">
            查看
          </el-button>
          <PermissionButton
            permission="product.update"
            link
            type="primary"
            @click="router.push(`/products/${row.productBusinessId}/edit`)"
          >
            编辑
          </PermissionButton>
        </template>
      </el-table-column>
    </el-table>
    <template #pager>
      <ListPager
        :page="page"
        :size="size"
        :count="rows.length"
        :sizes="sizes"
        @update:page="goPage"
        @update:size="changeSize"
      />
    </template>
  </ListPage>
</template>

<style scoped>
.keyword {
  width: 240px;
}

.status {
  width: 130px;
}
</style>
