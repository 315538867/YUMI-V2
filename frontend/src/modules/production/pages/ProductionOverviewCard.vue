<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { userMessageOf } from '@/platform/services/errorMessage'
import type { ProductionOverview } from '@/modules/production/contracts'
import { productionService } from '@/modules/production/services/productionService'
import CodeTag from '@/shared/components/CodeTag.vue'
import PageState from '@/shared/components/PageState.vue'

const overview = ref<ProductionOverview | null>(null)
const loading = ref(true)
const errorText = ref('')

const scheduledRate = computed(() =>
  rate(overview.value?.scheduledQuantity, overview.value?.requiredQuantity),
)
const fulfilledRate = computed(() =>
  rate(overview.value?.fulfilledQuantity, overview.value?.requiredQuantity),
)

function rate(value: number | undefined, total: number | undefined): string {
  if (value === undefined || total === undefined || total === 0) {
    return '—'
  }
  return `${((value / total) * 100).toFixed(1)}%`
}

function processRate(required: number, completed: number): number {
  if (required === 0) {
    return 0
  }
  return Math.min(100, Math.round((completed / required) * 100))
}

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    overview.value = await productionService.overview()
  } catch (error) {
    errorText.value = userMessageOf(error, '整体进度加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card class="overview-card" shadow="never">
    <template #header>
      <div class="head">
        <strong>整体进度</strong>
        <span class="hint"
          >以首个工序排班与已处理数量统计；有效订单 {{ overview?.orderCount ?? 0 }} 个</span
        >
      </div>
    </template>
    <PageState :loading="loading" :error="errorText" @retry="load">
      <template v-if="overview">
        <div class="metrics">
          <div class="metric">
            <small>真实需求</small>
            <strong>{{ overview.requiredQuantity }}</strong>
            <span>件</span>
            <em>订单应交总量</em>
          </div>
          <div class="metric">
            <small>已排班</small>
            <strong>{{ overview.scheduledQuantity }}</strong>
            <span>/ {{ overview.requiredQuantity }} 件</span>
            <em>覆盖 {{ scheduledRate }}</em>
          </div>
          <div class="metric">
            <small>生产事实</small>
            <strong>{{ overview.productionFactQuantity }}</strong>
            <span>件</span>
            <em>首个工序已处理</em>
          </div>
          <div class="metric">
            <small>已完成履约</small>
            <strong>{{ overview.fulfilledQuantity }}</strong>
            <span>/ {{ overview.requiredQuantity }} 件</span>
            <em>完成 {{ fulfilledRate }}</em>
          </div>
          <div class="metric">
            <small>待完成</small>
            <strong>{{ overview.remainingQuantity }}</strong>
            <span>件</span>
            <em>含在制、就绪与缺口</em>
          </div>
        </div>
        <div class="processes">
          <div v-for="process in overview.processes" :key="process.process" class="process">
            <div class="process-head">
              <CodeTag category="production.targetProcess" :code="process.process" />
              <span class="process-meta">
                需求 {{ process.requiredQuantity }} · 已排班 {{ process.scheduledQuantity }} ·
                已完成 {{ process.completedQuantity }} · 待完成 {{ process.remainingQuantity }}
              </span>
            </div>
            <el-progress
              :percentage="processRate(process.requiredQuantity, process.completedQuantity)"
              :stroke-width="6"
              :show-text="false"
              :aria-label="`${process.process} 完成进度`"
            />
          </div>
        </div>
      </template>
    </PageState>
  </el-card>
</template>

<style scoped>
.overview-card {
  margin-bottom: 12px;
}

.head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.metrics {
  display: grid;
  grid-template-columns: repeat(5, minmax(120px, 1fr));
  gap: 8px;
  margin-bottom: 12px;
}

.metric {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 8px 10px;
  background: var(--el-fill-color-lighter);
}

.metric small {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.metric strong {
  font-size: 20px;
  margin-right: 4px;
}

.metric span,
.metric em {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-style: normal;
}

.metric em {
  display: block;
  margin-top: 2px;
}

.processes {
  display: grid;
  grid-template-columns: repeat(3, minmax(180px, 1fr));
  gap: 12px;
}

.process-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
  gap: 8px;
}

.process-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
