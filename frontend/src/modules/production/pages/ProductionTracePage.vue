<script setup lang="ts">
import { ref } from 'vue'
import { userMessageOf } from '@/platform/services/errorMessage'
import type { OrderProductionTrace } from '@/modules/production/contracts'
import { productionService } from '@/modules/production/services/productionService'

const orderItemId = ref('')
const trace = ref<OrderProductionTrace | null>(null)
const offline = ref<Array<{ factKind: string; factBusinessId: string; productionSourceBusinessId: string; quantity: number; occurredAt: string; detail: string | null; destination: string }>>([])
const loading = ref(false)
const errorText = ref('')

async function load(): Promise<void> {
  if (orderItemId.value.trim() === '') {
    errorText.value = '请输入订单项标识'
    return
  }
  loading.value = true
  errorText.value = ''
  try {
    trace.value = await productionService.orderTrace(orderItemId.value.trim())
    const batches = await Promise.all(
      trace.value.demands.map((demand) =>
        productionService.offlineTransfers(demand.productionSourceBusinessId),
      ),
    )
    offline.value = batches.flat()
  } catch (error) {
    trace.value = null
    errorText.value = userMessageOf(error, '生产追溯加载失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section v-loading="loading" class="trace-page">
    <h1>订单生产追溯</h1>
    <div class="toolbar">
      <el-input v-model="orderItemId" placeholder="订单项业务标识" class="input" @keyup.enter="load" />
      <el-button type="primary" @click="load">追溯</el-button>
    </div>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <template v-if="trace">
      <p class="meta">订单 {{ trace.orderBusinessId }} / 订单项 {{ trace.orderItemBusinessId }}</p>
      <el-card v-for="demand in trace.demands" :key="demand.demandBusinessId" class="demand">
        <template #header>
          <strong>{{ demand.demandBusinessId }}</strong>
          <span class="meta"> · {{ demand.demandType }} · {{ demand.status }}</span>
        </template>
        <p class="meta">
          来源 {{ demand.productionSourceBusinessId }} · 物理根 {{ demand.physicalRootSourceBusinessId }}
        </p>
        <el-table :data="demand.tasks" border size="small">
          <el-table-column prop="taskNo" label="任务" width="130" />
          <el-table-column prop="taskType" label="类型" width="150" />
          <el-table-column prop="workDate" label="工作日期" width="120" />
          <el-table-column prop="processCode" label="工序" width="120" />
          <el-table-column prop="plannedQuantity" label="计划" width="80" />
          <el-table-column prop="status" label="状态" width="120" />
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
          <el-table-column prop="factKind" label="事实" width="160" />
          <el-table-column prop="factBusinessId" label="事实标识" min-width="200" />
          <el-table-column prop="quantity" label="数量" width="80" />
          <el-table-column prop="occurredAt" label="发生时间" width="220" />
          <el-table-column prop="detail" label="说明" min-width="160" />
        </el-table>
        <h3>转线下记录</h3>
        <el-table :data="offline.filter((row) => row.productionSourceBusinessId === demand.productionSourceBusinessId)" border size="small">
          <el-table-column prop="factKind" label="类型" width="160" />
          <el-table-column prop="quantity" label="数量" width="80" />
          <el-table-column prop="destination" label="去向" width="190" />
          <el-table-column prop="detail" label="原因" min-width="150" />
          <el-table-column prop="occurredAt" label="时间" width="220" />
        </el-table>
      </el-card>
    </template>
  </section>
</template>

<style scoped>
.trace-page {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 20px;
}

h1 {
  margin: 0 0 12px;
  font-size: 18px;
}

.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.input {
  width: 280px;
}

.meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

h3 { margin: 14px 0 8px; font-size: 14px; }

.demand {
  margin-bottom: 12px;
}

.reasons {
  margin: 8px 0;
  color: var(--el-color-warning);
  font-size: 13px;
}

.facts {
  margin-top: 10px;
}

.alert {
  margin-bottom: 12px;
}
</style>
