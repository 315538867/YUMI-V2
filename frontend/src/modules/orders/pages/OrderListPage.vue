<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { codeLabel, codesOf } from '@/platform/display/codeCatalog'
import { useAuthStore } from '@/platform/stores/auth'
import { useListQuery } from '@/platform/composables/useListQuery'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import type { OrderStatus, OrderSummaryView } from '@/modules/orders/contracts'
import { orderService } from '@/modules/orders/services/orderService'
import CodeTag from '@/shared/components/CodeTag.vue'
import ListPager from '@/shared/components/ListPager.vue'
import MoneyText from '@/shared/components/MoneyText.vue'
import type { SelectOption, TableColumn } from '@/shared/ui/types'
import UiButton from '@/shared/ui/UiButton.vue'
import UiCapacityBar from '@/shared/ui/UiCapacityBar.vue'
import UiDataTable from '@/shared/ui/UiDataTable.vue'
import UiPageBar from '@/shared/ui/UiPageBar.vue'
import UiSearchField from '@/shared/ui/UiSearchField.vue'
import UiSelectField from '@/shared/ui/UiSelectField.vue'

const auth = useAuthStore()
const router = useRouter()
const { loading, errorText, run } = useAsyncLoad()

const rows = ref<OrderSummaryView[]>([])
const canCreate = computed(() => auth.hasPermission('order.create'))
const canIntake = computed(() => auth.hasPermission('order.legacy.intake'))

const statusOptions: SelectOption[] = codesOf('order.status').map((code) => ({
  value: code,
  label: codeLabel('order.status', code),
}))

const { filters, page, size, sizes, search, reset, goPage, changeSize } = useListQuery(
  { keyword: '', status: '' as OrderStatus | '' },
  { onSearch: () => void load() },
)

const columns: TableColumn[] = [
  { key: 'orderNo', label: '订单 / 客户', minWidth: 220 },
  { key: 'productNames', label: '商品', minWidth: 160 },
  { key: 'expectedDeliveryDate', label: '预计交付', width: 120 },
  { key: 'currentReceivable', label: '当前应收', width: 130, align: 'right' },
  { key: 'delivery', label: '交付 / 付款', width: 190 },
  { key: 'netReceipts', label: '净收款', width: 130, align: 'right' },
  { key: 'status', label: '状态', width: 110, align: 'right' },
  { key: 'actions', label: '操作', width: 90, align: 'right' },
]

const tableRows = computed<Record<string, unknown>[]>(() =>
  rows.value.map((row) => ({ ...row })),
)

function orderOf(row: Record<string, unknown>): OrderSummaryView {
  return row as unknown as OrderSummaryView
}

function moneyOf(value: unknown): string | number | null {
  return value as string | number | null
}

async function load(): Promise<void> {
  await run(async () => {
    rows.value = await orderService.list(filters.keyword, filters.status, page.value, size.value)
  }, '订单列表加载失败')
}

function runSearch(): void {
  void search()
}

void load()
</script>

<template>
  <div class="order-list">
    <UiPageBar
      :breadcrumb="['订单']"
      :meta="`本页 ${rows.length} 个订单`"
    >
      <template #actions>
        <UiButton v-if="canIntake" @click="router.push('/orders/opening')">已有订单接管</UiButton>
        <UiButton v-if="canCreate" variant="primary" @click="router.push('/orders/new')">
          新增订单
        </UiButton>
      </template>
    </UiPageBar>

    <div class="order-filters">
      <UiSearchField
        v-model="filters.keyword"
        placeholder="订单号、客户或商品"
        class="order-keyword"
        @search="runSearch"
      />
      <UiSelectField
        v-model="filters.status"
        :options="statusOptions"
        placeholder="全部状态"
        aria-label="订单状态筛选"
        class="order-status"
      />
      <UiButton size="sm" variant="primary" @click="runSearch">查询</UiButton>
      <UiButton size="sm" @click="reset">重置</UiButton>
    </div>

    <div class="order-table">
      <UiDataTable
        :columns="columns"
        :rows="tableRows"
        row-key="orderBusinessId"
        :loading="loading"
        empty-text="没有匹配的订单"
      >
        <template #orderNo="{ row }">
          <span class="order-no">{{ orderOf(row).orderNo }}</span>
          <span class="order-customer">{{ orderOf(row).customerName }}</span>
        </template>
        <template #currentReceivable="{ row }">
          <MoneyText :value="moneyOf(row.currentReceivable)" />
        </template>
        <template #delivery="{ row }">
          <UiCapacityBar
            :value="orderOf(row).shippedQuantity"
            :max="Math.max(orderOf(row).requiredQuantity, 1)"
            :label="`已交付 ${orderOf(row).shippedQuantity} / ${orderOf(row).requiredQuantity}`"
          />
          <span class="order-payment">
            付款{{ orderOf(row).paymentFulfilled ? '已收清' : '未收清' }}
          </span>
        </template>
        <template #netReceipts="{ row }">
          <MoneyText :value="moneyOf(row.netReceipts)" />
        </template>
        <template #status="{ row }">
          <CodeTag category="order.status" :code="orderOf(row).status" />
        </template>
        <template #actions="{ row }">
          <UiButton size="sm" variant="ghost" @click="router.push(`/orders/${orderOf(row).orderBusinessId}`)">
            查看
          </UiButton>
        </template>
      </UiDataTable>
      <div v-if="errorText" class="order-error">{{ errorText }}</div>
      <ListPager
        :page="page"
        :size="size"
        :count="rows.length"
        :sizes="sizes"
        @update:page="goPage"
        @update:size="changeSize"
      />
    </div>
  </div>
</template>

<style scoped>
.order-list {
  display: flex;
  flex-direction: column;
  gap: var(--yumi-ds-space-md);
}

.order-filters {
  display: flex;
  align-items: center;
  gap: var(--yumi-ds-space-sm);
  flex-wrap: wrap;
}

.order-keyword {
  width: 240px;
}

.order-status {
  width: 150px;
}

.order-table {
  min-height: 0;
}

.order-no {
  display: block;
  color: var(--yumi-ds-ink);
  font-weight: var(--yumi-ds-font-weight-semibold);
}

.order-customer,
.order-payment {
  display: block;
  margin-top: var(--yumi-ds-space-2xs);
  color: var(--yumi-ds-muted);
  font-size: var(--yumi-ds-font-size-meta);
}

.order-error {
  margin-top: var(--yumi-ds-space-md);
  color: var(--yumi-ds-status-repair);
  font-size: var(--yumi-ds-font-size-label);
}
</style>
