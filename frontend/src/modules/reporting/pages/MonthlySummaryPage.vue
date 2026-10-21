<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { userMessageOf } from '@/platform/services/errorMessage'
import type {
  AfterSalesMonthlySummary,
  FreshnessView,
  MonthlyOperatingSummary,
} from '@/modules/reporting/contracts'
import { reportingService } from '@/modules/reporting/services/reportingService'

const month = ref(new Date().toISOString().slice(0, 7))
const summary = ref<MonthlyOperatingSummary | null>(null)
const freshness = ref<FreshnessView | null>(null)
const afterSales = ref<AfterSalesMonthlySummary | null>(null)
const loading = ref(false)
const errorText = ref('')

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    ;[summary.value, freshness.value, afterSales.value] = await Promise.all([
      reportingService.monthlySummary(month.value),
      reportingService.freshness(),
      reportingService.afterSalesSummary(month.value),
    ])
  } catch (error) {
    errorText.value = userMessageOf(error, '月度报表加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section v-loading="loading" class="summary-page">
    <div class="toolbar">
      <el-input v-model="month" type="month" class="month" />
      <el-button type="primary" @click="load">加载月汇总</el-button>
    </div>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <template v-if="summary">
      <el-alert
        v-if="summary.costIncomplete"
        type="warning"
        :closable="false"
        title="本月存在订单但成本归集不完整；成本合计与利润提示仅供参考，不补齐推断值。"
        class="alert"
      />
      <el-alert
        v-if="summary.lateSources"
        type="warning"
        :closable="false"
        title="存在失败来源，报表可能延迟；请到报表管理查看并重试。"
        class="alert"
      />

      <div class="cards">
        <el-card><template #header>订单</template>
          <div>订单数 {{ summary.orderCount }}</div>
          <div>当前应收 {{ summary.orderReceivable }}</div>
          <div>净收款 {{ summary.orderNetReceipts }}</div>
        </el-card>
        <el-card><template #header>生产</template>
          <div>需求 {{ summary.productionRequiredQuantity }} / 合格完成 {{ summary.productionFulfilledQuantity }}</div>
          <div>缺口 {{ summary.productionGapQuantity }}</div>
          <div>销毁 {{ summary.destroyedQuantity }} / 转线下 {{ summary.offlineInventoryQuantity }}</div>
          <div>系统内 {{ summary.productionInSystemQuantity }} / 已消费 {{ summary.productionConsumedQuantity }}</div>
        </el-card>
        <el-card><template #header>成本</template>
          <div>归集成本 {{ summary.costTotal }}</div>
          <div>{{ summary.costIncomplete ? '口径不完整' : '口径完整' }}</div>
          <div>经营费用 {{ summary.operatingExpense }}</div>
        </el-card>
        <el-card><template #header>工资</template>
          <div>应付 {{ summary.payrollPayable }}</div>
          <div>已付 {{ summary.payrollPaid }}</div>
          <div>工资退回 {{ summary.payrollCashReturn }}</div>
        </el-card>
        <el-card><template #header>现金</template>
          <div>流入 {{ summary.cashInflow }}</div>
          <div>流出 {{ summary.cashOutflow }}</div>
          <div>净额 {{ summary.cashNet }}（不是利润）</div>
        </el-card>
        <el-card><template #header>售后</template>
          <div>案例 {{ afterSales?.caseCount ?? 0 }}</div>
          <div>处理中 {{ afterSales?.openCount ?? 0 }} / 已完成 {{ afterSales?.completedCount ?? 0 }}</div>
          <div>赔付退款 {{ summary.afterSalesPayments }}</div>
        </el-card>
      </div>

      <h2>数据时效</h2>
      <p class="meta">
        当前投影版本 {{ freshness?.versionBusinessId ?? '未切换' }}
        <span v-if="freshness?.activatedAt"> · 切换于 {{ freshness.activatedAt }}</span>
      </p>
      <el-table :data="freshness?.feeds ?? []" border size="small">
        <el-table-column prop="feedCode" label="来源" width="150" />
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column prop="lagSeconds" label="延迟秒数" width="110" />
        <el-table-column prop="lastError" label="最近错误" min-width="260" />
      </el-table>
      <p class="hint">
        应收、净收款、成本与现金分列；工资退回不充当经营收入，现金净额不叫利润。
      </p>
    </template>
  </section>
</template>

<style scoped>
.summary-page { background: #fff; border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 20px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.month { width: 160px; }
.cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 12px; }
h2 { margin: 22px 0 8px; font-size: 16px; }
.meta { color: var(--el-text-color-secondary); font-size: 12px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; }
.alert { margin-bottom: 12px; }
</style>
