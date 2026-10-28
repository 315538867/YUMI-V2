<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import type { ProductionOverview } from '@/modules/production/contracts'
import { productionService } from '@/modules/production/services/productionService'
import CodeTag from '@/shared/components/CodeTag.vue'
import PageState from '@/shared/components/PageState.vue'

const { loading, errorText, run } = useAsyncLoad(true)

const overview = ref<ProductionOverview | null>(null)

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
  await run(async () => {
    overview.value = await productionService.overview()
  }, '整体进度加载失败')
}

onMounted(load)
</script>

<template>
  <el-card class="overview-card" shadow="never">
    <template #header>
      <div class="yumi-page-heading">
        <strong>整体进度</strong>
        <span class="yumi-hint"
          >以首个工序排班与已处理数量统计；有效订单 {{ overview?.orderCount ?? 0 }} 个</span
        >
      </div>
    </template>
    <PageState :loading="loading" :error="errorText" @retry="load">
      <template v-if="overview">
        <div class="yumi-stat-grid yumi-stat-grid--5">
          <div class="yumi-stat-card">
            <span class="yumi-stat-card__label">真实需求</span>
            <span class="yumi-stat-card__value">
              {{ overview.requiredQuantity }}<small>件</small>
            </span>
            <span class="yumi-stat-card__note">订单应交总量</span>
          </div>
          <div class="yumi-stat-card">
            <span class="yumi-stat-card__label">已排班</span>
            <span class="yumi-stat-card__value">
              {{ overview.scheduledQuantity }}<small>/ {{ overview.requiredQuantity }} 件</small>
            </span>
            <span class="yumi-stat-card__note">覆盖 {{ scheduledRate }}</span>
          </div>
          <div class="yumi-stat-card">
            <span class="yumi-stat-card__label">生产事实</span>
            <span class="yumi-stat-card__value">
              {{ overview.productionFactQuantity }}<small>件</small>
            </span>
            <span class="yumi-stat-card__note">首个工序已处理</span>
          </div>
          <div class="yumi-stat-card">
            <span class="yumi-stat-card__label">已完成履约</span>
            <span class="yumi-stat-card__value">
              {{ overview.fulfilledQuantity }}<small>/ {{ overview.requiredQuantity }} 件</small>
            </span>
            <span class="yumi-stat-card__note">完成 {{ fulfilledRate }}</span>
          </div>
          <div class="yumi-stat-card">
            <span class="yumi-stat-card__label">待完成</span>
            <span class="yumi-stat-card__value">
              {{ overview.remainingQuantity }}<small>件</small>
            </span>
            <span class="yumi-stat-card__note">含在制、就绪与缺口</span>
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
  margin-bottom: var(--yumi-space-3);
}

.processes {
  display: grid;
  grid-template-columns: repeat(3, minmax(180px, 1fr));
  gap: var(--yumi-space-3);
}

.process-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--yumi-space-2);
  gap: var(--yumi-space-2);
}

.process-meta {
  color: var(--yumi-text-secondary);
  font-size: var(--yumi-font-size-sm);
}
</style>
