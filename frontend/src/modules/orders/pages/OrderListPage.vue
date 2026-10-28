<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useListQuery } from '@/platform/composables/useListQuery'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import type { OrderStatus, OrderSummaryView } from '@/modules/orders/contracts'
import { orderService } from '@/modules/orders/services/orderService'
import CodeTag from '@/shared/components/CodeTag.vue'
import ListPage from '@/shared/components/ListPage.vue'
import ListPager from '@/shared/components/ListPager.vue'
import MoneyText from '@/shared/components/MoneyText.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'

const { loading, errorText, run } = useAsyncLoad()

const router = useRouter()
const rows = ref<OrderSummaryView[]>([])
const { filters, page, size, sizes, search, reset, goPage, changeSize } = useListQuery(
  { keyword: '', status: '' as OrderStatus | '' },
  { onSearch: () => void load() },
)

async function load(): Promise<void> {
  await run(async () => {
    rows.value = await orderService.list(filters.keyword, filters.status, page.value, size.value)
  }, '订单列表加载失败')
}

void load()
</script>

<template>
  <ListPage
    class="order-list"
    title="订单"
    hint="客户约定、交付与收款"
    panel-title="订单列表"
    panel-hint="交付履约与付款履约分别查看"
    :loading="loading"
    :error="errorText"
    @retry="load"
  >
    <template #filters>
      <el-input
        v-model="filters.keyword"
        placeholder="订单号、客户或商品"
        clearable
        class="keyword"
        @keyup.enter="search"
      />
      <el-select
        v-model="filters.status"
        placeholder="全部状态"
        clearable
        class="status"
        aria-label="订单状态筛选"
      >
        <el-option label="已确认" value="CONFIRMED" />
        <el-option label="履约中" value="IN_FULFILLMENT" />
        <el-option label="已完成" value="COMPLETED" />
        <el-option label="已取消" value="CANCELLED" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </template>
    <template #actions>
      <PermissionButton permission="order.create" type="primary" @click="router.push('/orders/new')"
        >新增订单</PermissionButton
      >
      <PermissionButton permission="order.legacy.intake" @click="router.push('/orders/opening')">
        已有订单接管
      </PermissionButton>
    </template>
    <el-table :data="rows" empty-text="没有匹配的订单" border>
      <el-table-column label="订单 / 客户" min-width="220">
        <template #default="{ row }">
          <span class="yumi-cell-title">{{ row.orderNo }}</span>
          <span class="yumi-cell-sub">{{ row.customerName }}</span>
        </template>
      </el-table-column>
      <el-table-column label="商品" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="yumi-cell-title">{{ row.productNames }}</span>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="150">
        <template #default="{ row }"
          ><CodeTag category="order.origin" :code="row.origin"
        /></template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }"
          ><CodeTag category="order.status" :code="row.status"
        /></template>
      </el-table-column>
      <el-table-column prop="orderDate" label="订单日期" width="120" />
      <el-table-column prop="expectedDeliveryDate" label="预计交付" width="120" />
      <el-table-column label="当前应收" width="150">
        <template #default="{ row }"><MoneyText :value="row.currentReceivable" /></template>
      </el-table-column>
      <el-table-column label="净收款" width="150">
        <template #default="{ row }"><MoneyText :value="row.netReceipts" /></template>
      </el-table-column>
      <el-table-column label="交付 / 付款" width="140">
        <template #default="{ row }">
          <span class="yumi-cell-title">
            {{ row.deliveryFulfilled ? '已交付' : '未交付' }}
          </span>
          <span class="yumi-cell-sub">
            {{ row.paymentFulfilled ? '已收清' : '未收清' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="router.push(`/orders/${row.orderBusinessId}`)"
            >查看</el-button
          >
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
