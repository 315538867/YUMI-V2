<script setup lang="ts">
import { ref } from 'vue'
import { useListQuery } from '@/platform/composables/useListQuery'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import type {
  AfterSalesMonthlySummary,
  FreshnessView,
  MonthlyOperatingSummary,
} from '@/modules/reporting/contracts'
import { reportingService } from '@/modules/reporting/services/reportingService'
import CodeTag from '@/shared/components/CodeTag.vue'
import PageState from '@/shared/components/PageState.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'
import PageShell from '@/shared/components/PageShell.vue'

const { loading, errorText, run } = useAsyncLoad()

const summary = ref<MonthlyOperatingSummary | null>(null)
const freshness = ref<FreshnessView | null>(null)
const afterSales = ref<AfterSalesMonthlySummary | null>(null)
const { filters, search } = useListQuery(
  { month: new Date().toISOString().slice(0, 7) },
  { paged: false, onSearch: () => void load() },
)

async function load(): Promise<void> {
  await run(async () => {
    ;[summary.value, freshness.value, afterSales.value] = await Promise.all([
      reportingService.monthlySummary(filters.month),
      reportingService.freshness(),
      reportingService.afterSalesSummary(filters.month),
    ])
  }, '月度报表加载失败')
}

void load()
</script>

<template>
  <PageShell title="月度汇总" hint="按实际收付与成本发生日期统计，不称利润">
    <WorkspacePage class="summary-page">
      <div class="yumi-toolbar">
        <el-input
          v-model="filters.month"
          type="month"
          class="month"
          aria-label="统计月份"
          @change="search"
        />
        <el-button type="primary" @click="search">加载月汇总</el-button>
      </div>
      <PageState :loading="loading" :error="errorText" @retry="load">
        <template v-if="summary">
          <el-alert
            v-if="summary.costIncomplete"
            type="warning"
            :closable="false"
            title="本月存在订单但成本归集不完整；成本合计与利润提示仅供参考，不补齐推断值。"
            class="yumi-alert"
          />
          <el-alert
            v-if="summary.lateSources"
            type="warning"
            :closable="false"
            title="存在失败来源，报表可能延迟；请到报表管理查看并重试。"
            class="yumi-alert"
          />

          <div class="yumi-card-grid">
            <el-card
              ><template #header>订单</template>
              <div>订单数 {{ summary.orderCount }}</div>
              <div>当前应收 {{ summary.orderReceivable }}</div>
              <div>净收款 {{ summary.orderNetReceipts }}</div>
            </el-card>
            <el-card
              ><template #header>生产</template>
              <div>
                需求 {{ summary.productionRequiredQuantity }} / 合格完成
                {{ summary.productionFulfilledQuantity }}
              </div>
              <div>缺口 {{ summary.productionGapQuantity }}</div>
              <div>
                销毁 {{ summary.destroyedQuantity }} / 转线下 {{ summary.offlineInventoryQuantity }}
              </div>
              <div>
                系统内 {{ summary.productionInSystemQuantity }} / 已消费
                {{ summary.productionConsumedQuantity }}
              </div>
            </el-card>
            <el-card
              ><template #header>成本</template>
              <div>归集成本 {{ summary.costTotal }}</div>
              <div>{{ summary.costIncomplete ? '口径不完整' : '口径完整' }}</div>
              <div>经营费用 {{ summary.operatingExpense }}</div>
            </el-card>
            <el-card
              ><template #header>工资</template>
              <div>应付 {{ summary.payrollPayable }}</div>
              <div>已付 {{ summary.payrollPaid }}</div>
              <div>工资退回 {{ summary.payrollCashReturn }}</div>
            </el-card>
            <el-card
              ><template #header>现金</template>
              <div>流入 {{ summary.cashInflow }}</div>
              <div>流出 {{ summary.cashOutflow }}</div>
              <div>净额 {{ summary.cashNet }}（不是利润）</div>
            </el-card>
            <el-card
              ><template #header>售后</template>
              <div>案例 {{ afterSales?.caseCount ?? 0 }}</div>
              <div>
                处理中 {{ afterSales?.openCount ?? 0 }} / 已完成
                {{ afterSales?.completedCount ?? 0 }}
              </div>
              <div>赔付退款 {{ summary.afterSalesPayments }}</div>
            </el-card>
          </div>

          <h2>数据时效</h2>
          <p class="yumi-meta">
            当前投影版本 {{ freshness?.versionBusinessId ?? '未切换' }}
            <span v-if="freshness?.activatedAt"> · 切换于 {{ freshness.activatedAt }}</span>
          </p>
          <el-table :data="freshness?.feeds ?? []" border size="small">
            <el-table-column label="来源" width="150">
              <template #default="{ row }"
                ><CodeTag category="reporting.feedCode" :code="row.feedCode"
              /></template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }"
                ><CodeTag category="reporting.feedStatus" :code="row.status"
              /></template>
            </el-table-column>
            <el-table-column prop="lagSeconds" label="延迟秒数" width="110" />
            <el-table-column prop="lastError" label="最近错误" min-width="260" />
          </el-table>
          <p class="yumi-hint">
            应收、净收款、成本与现金分列；工资退回不充当经营收入，现金净额不叫利润。
          </p>
        </template>
      </PageState>
    </WorkspacePage>
  </PageShell>
</template>

<style scoped>
.month {
  width: 160px;
}
</style>
