<script setup lang="ts">
import { ref } from 'vue'
import { useListQuery } from '@/platform/composables/useListQuery'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import type { MonthlyOperatingSummary } from '@/modules/reporting/contracts'
import { reportingService } from '@/modules/reporting/services/reportingService'
import PageState from '@/shared/components/PageState.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'

const { loading, errorText, run } = useAsyncLoad()

const summary = ref<MonthlyOperatingSummary | null>(null)
const { filters, search } = useListQuery(
  { month: new Date().toISOString().slice(0, 7), theme: 'ORDER' },
  { paged: false, onSearch: () => void load() },
)
const themes = [
  { value: 'ORDER', label: '订单' },
  { value: 'PRODUCTION', label: '生产' },
  { value: 'AFTER_SALES', label: '售后' },
  { value: 'PAYROLL', label: '工资' },
  { value: 'COST', label: '成本' },
  { value: 'CASH', label: '现金' },
]

async function load(): Promise<void> {
  await run(async () => {
    summary.value = await reportingService.monthlySummary(filters.month)
  }, '主题报表加载失败')
}

void load()
</script>

<template>
  <WorkspacePage class="explorer-page">
    <div class="toolbar">
      <el-input
        v-model="filters.month"
        type="month"
        class="month"
        aria-label="统计月份"
        @change="search"
      />
      <el-select v-model="filters.theme" class="theme" aria-label="报表主题" @change="search">
        <el-option
          v-for="item in themes"
          :key="item.value"
          :label="item.label"
          :value="item.value"
        />
      </el-select>
      <el-button type="primary" @click="search">加载</el-button>
    </div>
    <PageState :loading="loading" :error="errorText" @retry="load">
      <el-descriptions v-if="summary" :column="2" border>
        <template v-if="filters.theme === 'ORDER'">
          <el-descriptions-item label="订单数">{{ summary.orderCount }}</el-descriptions-item>
          <el-descriptions-item label="当前应收">{{
            summary.orderReceivable
          }}</el-descriptions-item>
          <el-descriptions-item label="收款">{{ summary.orderReceipts }}</el-descriptions-item>
          <el-descriptions-item label="退款">{{ summary.orderRefunds }}</el-descriptions-item>
        </template>
        <template v-else-if="filters.theme === 'PRODUCTION'">
          <el-descriptions-item label="需求数量">{{
            summary.productionRequiredQuantity
          }}</el-descriptions-item>
          <el-descriptions-item label="合格完成">{{
            summary.productionFulfilledQuantity
          }}</el-descriptions-item>
          <el-descriptions-item label="缺口">{{
            summary.productionGapQuantity
          }}</el-descriptions-item>
          <el-descriptions-item label="销毁">{{ summary.destroyedQuantity }}</el-descriptions-item>
          <el-descriptions-item label="转线下">{{
            summary.offlineInventoryQuantity
          }}</el-descriptions-item>
          <el-descriptions-item label="系统内 / 已消费">
            {{ summary.productionInSystemQuantity }} / {{ summary.productionConsumedQuantity }}
          </el-descriptions-item>
        </template>
        <template v-else-if="filters.theme === 'AFTER_SALES'">
          <el-descriptions-item label="售后赔付退款">{{
            summary.afterSalesPayments
          }}</el-descriptions-item>
        </template>
        <template v-else-if="filters.theme === 'PAYROLL'">
          <el-descriptions-item label="工资应付">{{ summary.payrollPayable }}</el-descriptions-item>
          <el-descriptions-item label="工资已付">{{ summary.payrollPaid }}</el-descriptions-item>
          <el-descriptions-item label="工资退回">{{
            summary.payrollCashReturn
          }}</el-descriptions-item>
        </template>
        <template v-else-if="filters.theme === 'COST'">
          <el-descriptions-item label="归集成本">{{ summary.costTotal }}</el-descriptions-item>
          <el-descriptions-item label="口径完整">{{
            summary.costIncomplete ? '否' : '是'
          }}</el-descriptions-item>
          <el-descriptions-item label="经营费用">{{
            summary.operatingExpense
          }}</el-descriptions-item>
        </template>
        <template v-else>
          <el-descriptions-item label="现金流入">{{ summary.cashInflow }}</el-descriptions-item>
          <el-descriptions-item label="现金流出">{{ summary.cashOutflow }}</el-descriptions-item>
          <el-descriptions-item label="现金净额">{{ summary.cashNet }}</el-descriptions-item>
          <el-descriptions-item label="经营收入">{{
            summary.operatingIncome
          }}</el-descriptions-item>
        </template>
      </el-descriptions>
    </PageState>
    <p class="hint">主题按授权查看；金额口径不互相替代，现金净额不是利润。</p>
  </WorkspacePage>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.month {
  width: 160px;
}
.theme {
  width: 140px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.alert {
  margin-bottom: 12px;
}
</style>
