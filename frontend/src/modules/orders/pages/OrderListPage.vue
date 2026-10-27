<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { userMessageOf } from '@/platform/services/errorMessage'
import { useListQuery } from '@/platform/composables/useListQuery'
import type { OrderStatus, OrderSummaryView } from '@/modules/orders/contracts'
import { orderService } from '@/modules/orders/services/orderService'
import CodeTag from '@/shared/components/CodeTag.vue'
import ListPager from '@/shared/components/ListPager.vue'
import MoneyText from '@/shared/components/MoneyText.vue'
import PageState from '@/shared/components/PageState.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'

const router = useRouter()
const rows = ref<OrderSummaryView[]>([])
const loading = ref(false)
const errorText = ref('')
const { filters, page, size, sizes, search, reset, goPage, changeSize } = useListQuery(
  { keyword: '', status: '' as OrderStatus | '' },
  { onSearch: () => void load() },
)

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await orderService.list(filters.keyword, filters.status, page.value, size.value)
  } catch (error) {
    errorText.value = userMessageOf(error, '订单列表加载失败')
  } finally {
    loading.value = false
  }
}

void load()
</script>

<template>
  <WorkspacePage class="order-list">
    <h1>订单</h1>
    <div class="toolbar">
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
      <PermissionButton permission="order.create" @click="router.push('/orders/new')"
        >新增订单</PermissionButton
      >
      <PermissionButton permission="order.legacy.intake" @click="router.push('/orders/opening')">
        已有订单接管
      </PermissionButton>
    </div>
    <PageState :loading="loading" :error="errorText" @retry="load">
      <el-table :data="rows" empty-text="没有匹配的订单" border>
        <el-table-column prop="orderNo" label="订单号" width="150" />
        <el-table-column prop="customerName" label="客户" min-width="140" show-overflow-tooltip />
        <el-table-column prop="productNames" label="商品" min-width="160" show-overflow-tooltip />
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
        <el-table-column label="交付/付款" width="130">
          <template #default="{ row }">
            {{ row.deliveryFulfilled ? '已交付' : '未交付' }} /
            {{ row.paymentFulfilled ? '已收清' : '未收清' }}
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
      <ListPager
        :page="page"
        :size="size"
        :count="rows.length"
        :sizes="sizes"
        @update:page="goPage"
        @update:size="changeSize"
      />
    </PageState>
  </WorkspacePage>
</template>

<style scoped>
.order-list h1 {
  margin: 0 0 12px;
  font-size: 18px;
}
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
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
