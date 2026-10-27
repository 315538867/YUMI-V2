<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { userMessageOf } from '@/platform/services/errorMessage'
import { useListQuery } from '@/platform/composables/useListQuery'
import type { OrderProductionTrace, ProductionTraceSummary } from '@/modules/production/contracts'
import { productionService } from '@/modules/production/services/productionService'
import PageState from '@/shared/components/PageState.vue'
import ProductionTraceDetail from '@/modules/production/pages/ProductionTraceDetail.vue'

const summaries = ref<ProductionTraceSummary[]>([])
const traceCache = ref<Record<string, OrderProductionTrace>>({})
const traceLoading = ref<Record<string, boolean>>({})
const traceErrors = ref<Record<string, string>>({})
const loading = ref(false)
const loadingMore = ref(false)
const hasMore = ref(false)
const nextOffset = ref(0)
const pageSize = 50
const errorText = ref('')
const expandedOrders = ref<Set<string>>(new Set())
const { filters, search, reset } = useListQuery(
  { keyword: '' },
  { paged: false, onSearch: () => {} },
)

const filtered = computed(() => {
  const keyword = filters.keyword.trim().toLowerCase()
  if (keyword === '') {
    return summaries.value
  }
  return summaries.value.filter(
    (summary) =>
      summary.orderBusinessId.toLowerCase().includes(keyword) ||
      summary.orderNo.toLowerCase().includes(keyword) ||
      summary.orderItemBusinessId.toLowerCase().includes(keyword) ||
      summary.productNames.toLowerCase().includes(keyword),
  )
})

const groups = computed(() => {
  const map = new Map<string, ProductionTraceSummary[]>()
  for (const summary of filtered.value) {
    const items = map.get(summary.orderBusinessId) ?? []
    items.push(summary)
    map.set(summary.orderBusinessId, items)
  }
  return [...map.entries()]
    .map(([orderBusinessId, items]) => ({ orderBusinessId, items }))
    .sort((left, right) => right.items.length - left.items.length)
})

function isExpanded(orderBusinessId: string): boolean {
  return expandedOrders.value.has(orderBusinessId)
}

function toggle(orderBusinessId: string): void {
  const next = new Set(expandedOrders.value)
  if (next.has(orderBusinessId)) {
    next.delete(orderBusinessId)
  } else {
    next.add(orderBusinessId)
    const group = groups.value.find((item) => item.orderBusinessId === orderBusinessId)
    if (group) {
      void loadGroupTraces(group.items)
    }
  }
  expandedOrders.value = next
}

function expandAll(): void {
  expandedOrders.value = new Set(groups.value.map((group) => group.orderBusinessId))
  for (const group of groups.value) {
    void loadGroupTraces(group.items)
  }
}

function collapseAll(): void {
  expandedOrders.value = new Set()
}

async function loadTrace(orderItemBusinessId: string): Promise<void> {
  if (traceCache.value[orderItemBusinessId] !== undefined) {
    return
  }
  traceLoading.value = { ...traceLoading.value, [orderItemBusinessId]: true }
  traceErrors.value = { ...traceErrors.value, [orderItemBusinessId]: '' }
  try {
    const trace = await productionService.orderTrace(orderItemBusinessId)
    traceCache.value = { ...traceCache.value, [orderItemBusinessId]: trace }
  } catch (error) {
    traceErrors.value = {
      ...traceErrors.value,
      [orderItemBusinessId]: userMessageOf(error, '生产链路加载失败'),
    }
  } finally {
    traceLoading.value = { ...traceLoading.value, [orderItemBusinessId]: false }
  }
}

async function loadGroupTraces(items: ProductionTraceSummary[]): Promise<void> {
  await Promise.all(items.map((item) => loadTrace(item.orderItemBusinessId)))
}

async function load(reset = true): Promise<void> {
  if (reset) {
    loading.value = true
  } else {
    loadingMore.value = true
  }
  errorText.value = ''
  try {
    const page = await productionService.traceSummaries(reset ? 0 : nextOffset.value, pageSize)
    summaries.value = reset ? page : [...summaries.value, ...page]
    nextOffset.value = summaries.value.length
    hasMore.value = page.length === pageSize
    if (reset && groups.value.length > 0) {
      const first = groups.value[0]!
      expandedOrders.value = new Set([first.orderBusinessId])
      await loadGroupTraces(first.items)
    }
  } catch (error) {
    if (reset) {
      summaries.value = []
    }
    errorText.value = userMessageOf(error, '生产追溯加载失败')
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

onMounted(() => {
  void load(true)
})
</script>

<template>
  <section class="trace-page">
    <h1>订单生产追溯</h1>
    <div class="toolbar">
      <el-input
        v-model="filters.keyword"
        placeholder="订单、订单项或商品"
        clearable
        class="input"
        aria-label="订单、订单项或商品"
        @keyup.enter="search"
      />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="load(true)">刷新</el-button>
      <el-button @click="reset">重置</el-button>
      <el-button @click="expandAll">展开全部</el-button>
      <el-button @click="collapseAll">收起全部</el-button>
    </div>
    <PageState
      :loading="loading"
      :error="errorText"
      :empty="!loading && errorText === '' && groups.length === 0"
      :empty-text="filters.keyword.trim() === '' ? '暂无生产追溯记录' : '没有匹配的生产记录'"
      @retry="load(true)"
    >
      <el-card
        v-for="group in groups"
        :key="group.orderBusinessId"
        class="order-card"
        shadow="never"
      >
        <template #header>
          <div class="order-head">
            <div>
              <strong>订单 {{ group.items[0]?.orderNo || group.orderBusinessId }}</strong>
              <span class="meta">
                · {{ group.items[0]?.productNames || '未关联商品' }} ·
                {{ group.items.length }} 个订单项
              </span>
            </div>
            <el-button link type="primary" @click="toggle(group.orderBusinessId)">
              {{ isExpanded(group.orderBusinessId) ? '收起链路' : '查看链路' }}
            </el-button>
          </div>
        </template>
        <div v-if="isExpanded(group.orderBusinessId)" class="order-body">
          <div v-for="item in group.items" :key="item.orderItemBusinessId" class="trace-slot">
            <ProductionTraceDetail
              v-if="traceCache[item.orderItemBusinessId]"
              :trace="traceCache[item.orderItemBusinessId]!"
            />
            <el-alert
              v-else-if="traceErrors[item.orderItemBusinessId]"
              type="error"
              :closable="false"
              :title="traceErrors[item.orderItemBusinessId]"
            >
              <template #default>
                <el-button size="small" @click="loadTrace(item.orderItemBusinessId)">
                  重试
                </el-button>
              </template>
            </el-alert>
            <p v-else class="meta">
              {{ traceLoading[item.orderItemBusinessId] ? '正在加载链路…' : '等待加载链路' }}
            </p>
          </div>
        </div>
        <p v-else class="meta">展开查看需求、任务与生产事实链路。</p>
      </el-card>
      <div v-if="hasMore" class="load-more">
        <el-button :loading="loadingMore" @click="load(false)">加载更多</el-button>
      </div>
    </PageState>
  </section>
</template>

<style scoped>
.trace-page {
  background: var(--yumi-surface);
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
  align-items: center;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.input {
  width: 260px;
}

.order-card {
  margin-bottom: 12px;
}

.order-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.meta {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.order-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.trace-slot {
  display: flex;
  flex-direction: column;
}

.load-more {
  display: flex;
  justify-content: center;
}
</style>
