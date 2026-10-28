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
function deliveryPercent(row: OrderSummaryView): number {
  return row.requiredQuantity === 0
    ? 0
    : Math.min(100, Math.round((row.shippedQuantity / row.requiredQuantity) * 100))
}

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
      <el-table-column label="商品" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="yumi-cell-title">{{ row.productNames }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="expectedDeliveryDate" label="预计交付" width="120" />
      <el-table-column label="当前应收" width="130">
        <template #default="{ row }"><MoneyText :value="row.currentReceivable" /></template>
      </el-table-column>
      <el-table-column label="交付 / 付款" width="180">
        <template #default="{ row }">
          <div class="progress-meta">
            <span :class="row.deliveryFulfilled ? 'yumi-text-positive' : 'yumi-text-pending'">
              {{ row.deliveryFulfilled ? '已交付' : '待交付' }}
            </span>
            <span>{{ row.shippedQuantity }} / {{ row.requiredQuantity }}</span>
          </div>
          <el-progress
            :percentage="deliveryPercent(row)"
            :show-text="false"
            :stroke-width="6"
            :aria-label="`订单 ${row.orderNo} 交付进度`"
          />
          <span class="yumi-cell-sub"> 付款{{ row.paymentFulfilled ? '已收清' : '未收清' }} </span>
        </template>
      </el-table-column>
      <el-table-column label="净收款" width="130">
        <template #default="{ row }"><MoneyText :value="row.netReceipts" /></template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }"
          ><CodeTag category="order.status" :code="row.status"
        /></template>
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
.progress-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--yumi-space-2);
  margin-bottom: var(--yumi-space-1);
  color: var(--yumi-text-secondary);
  font-size: var(--yumi-font-size-xs);
}

.progress-meta + :deep(.el-progress) {
  margin-bottom: var(--yumi-space-1);
}

.keyword {
  width: 240px;
}
.status {
  width: 130px;
}
</style>
