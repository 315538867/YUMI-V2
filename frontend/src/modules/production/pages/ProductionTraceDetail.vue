<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { userMessageOf } from '@/platform/services/errorMessage'
import type { OrderProductionTrace } from '@/modules/production/contracts'
import { productionService } from '@/modules/production/services/productionService'
import CodeTag from '@/shared/components/CodeTag.vue'
import CodeLabel from '@/shared/components/CodeLabel.vue'
import DateTimeText from '@/shared/components/DateTimeText.vue'

const props = defineProps<{ trace: OrderProductionTrace }>()

const offline = ref<
  Array<{
    factKind: string
    factBusinessId: string
    productionSourceBusinessId: string
    quantity: number
    occurredAt: string
    detail: string | null
    destination: string
  }>
>([])
const offlineLoading = ref(false)
const offlineError = ref('')

async function loadOffline(): Promise<void> {
  offlineLoading.value = true
  offlineError.value = ''
  try {
    const batches = await Promise.all(
      props.trace.demands.map((demand) =>
        productionService.offlineTransfers(demand.productionSourceBusinessId),
      ),
    )
    offline.value = batches.flat()
  } catch (error) {
    offlineError.value = userMessageOf(error, '转线下记录加载失败')
  } finally {
    offlineLoading.value = false
  }
}

onMounted(loadOffline)
</script>

<template>
  <div class="trace-detail">
    <p class="meta">订单项 {{ trace.orderItemBusinessId }}</p>
    <el-alert
      v-if="offlineError"
      type="error"
      :closable="false"
      :title="offlineError"
      class="alert"
    />
    <el-card v-for="demand in trace.demands" :key="demand.demandBusinessId" class="demand">
      <template #header>
        <strong>{{ demand.demandBusinessId }}</strong>
        <span class="meta">
          · <CodeTag category="production.demandType" :code="demand.demandType" /> ·
          <CodeTag category="production.demandStatus" :code="demand.status" />
        </span>
      </template>
      <p class="meta">
        来源 {{ demand.productionSourceBusinessId }} · 物理根
        {{ demand.physicalRootSourceBusinessId }}
      </p>
      <el-table :data="demand.tasks" border size="small">
        <el-table-column prop="taskNo" label="任务" width="130" />
        <el-table-column label="类型" width="150">
          <template #default="{ row }">
            <CodeTag category="production.taskType" :code="row.taskType" />
          </template>
        </el-table-column>
        <el-table-column prop="workDate" label="工作日期" width="120" />
        <el-table-column label="工序" width="120">
          <template #default="{ row }">
            <CodeTag category="production.targetProcess" :code="row.processCode" />
          </template>
        </el-table-column>
        <el-table-column prop="plannedQuantity" label="计划" width="80" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <CodeTag category="production.taskStatus" :code="row.status" />
          </template>
        </el-table-column>
        <el-table-column label="明细结果" min-width="260">
          <template #default="{ row }">
            <div v-for="item in row.items" :key="item.taskItemBusinessId">
              合格 {{ item.qualifiedQuantity }} / 返工 {{ item.reworkQuantity }} / 报废
              {{ item.scrappedQuantity }} / 未完成 {{ item.unfinishedQuantity }}
            </div>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="demand.gapReasons.length" class="reasons">
        缺口原因：{{ demand.gapReasons.join('；') }}
      </div>
      <div v-if="demand.delayReasons.length" class="reasons">
        延迟原因：{{ demand.delayReasons.join('；') }}
      </div>
      <el-table :data="demand.facts" border size="small" class="facts">
        <el-table-column label="事实" width="160">
          <template #default="{ row }">
            <CodeLabel category="production.factKind" :code="row.factKind" fallback="其他事实" />
          </template>
        </el-table-column>
        <el-table-column prop="factBusinessId" label="事实标识" min-width="200" />
        <el-table-column prop="quantity" label="数量" width="80" />
        <el-table-column label="发生时间" width="220">
          <template #default="{ row }"><DateTimeText :value="row.occurredAt" /></template>
        </el-table-column>
        <el-table-column prop="detail" label="说明" min-width="160" />
      </el-table>
      <h3>转线下记录</h3>
      <el-table
        v-loading="offlineLoading"
        :data="
          offline.filter(
            (row) => row.productionSourceBusinessId === demand.productionSourceBusinessId,
          )
        "
        border
        size="small"
        empty-text="无转线下记录"
      >
        <el-table-column label="类型" width="160">
          <template #default="{ row }">
            <CodeLabel category="production.factKind" :code="row.factKind" fallback="其他事实" />
          </template>
        </el-table-column>
        <el-table-column prop="quantity" label="数量" width="80" />
        <el-table-column label="去向" width="190">
          <template #default="{ row }">
            <CodeLabel
              category="production.physicalDestination"
              :code="row.destination"
              :fallback="row.destination"
            />
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="原因" min-width="150" />
        <el-table-column label="时间" width="220">
          <template #default="{ row }"><DateTimeText :value="row.occurredAt" /></template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.trace-detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.meta {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.demand {
  margin-bottom: 4px;
}

.reasons {
  margin: 8px 0;
  color: var(--el-color-warning);
  font-size: 13px;
}

.facts {
  margin-top: 10px;
}

h3 {
  margin: 14px 0 8px;
  font-size: 14px;
}

.alert {
  margin-bottom: 12px;
}
</style>
