<script setup lang="ts">
import { ref } from 'vue'
import { userMessageOf } from '@/platform/services/errorMessage'
import { useListQuery } from '@/platform/composables/useListQuery'
import type { CostEntryView, CostSubjectSummary } from '@/modules/finance/costledger/contracts'
import { costLedgerService } from '@/modules/finance/costledger/services/costLedgerService'
import CodeTag from '@/shared/components/CodeTag.vue'
import DateTimeText from '@/shared/components/DateTimeText.vue'
import MoneyText from '@/shared/components/MoneyText.vue'
import PageState from '@/shared/components/PageState.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'

const entries = ref<CostEntryView[]>([])
const summary = ref<CostSubjectSummary | null>(null)
const loading = ref(false)
const errorText = ref('')
const { filters, search, reset } = useListQuery(
  {
    subjectType: 'ORDER',
    subjectId: '',
    startDate: new Date().toISOString().slice(0, 8) + '01',
    endDate: new Date().toISOString().slice(0, 10),
  },
  { paged: false, onSearch: () => void load() },
)

async function load(): Promise<void> {
  if (filters.subjectId.trim() === '') {
    entries.value = []
    summary.value = null
    errorText.value = ''
    return
  }
  loading.value = true
  errorText.value = ''
  try {
    ;[entries.value, summary.value] = await Promise.all([
      costLedgerService.entries(
        filters.subjectType,
        filters.subjectId.trim(),
        filters.startDate,
        filters.endDate,
      ),
      costLedgerService.summary(
        filters.subjectType,
        filters.subjectId.trim(),
        filters.startDate,
        filters.endDate,
      ),
    ])
  } catch (error) {
    errorText.value = userMessageOf(error, '成本明细加载失败')
  } finally {
    loading.value = false
  }
}

void load()
</script>

<template>
  <WorkspacePage class="entries-page">
    <h1>成本明细与汇总</h1>
    <div class="toolbar">
      <el-select v-model="filters.subjectType" class="subject-type" aria-label="成本主体类型">
        <el-option label="订单" value="ORDER" />
        <el-option label="员工" value="EMPLOYEE" />
        <el-option label="商品" value="PRODUCT" />
      </el-select>
      <el-input
        v-model="filters.subjectId"
        placeholder="主体标识"
        class="subject"
        aria-label="成本主体标识"
        @keyup.enter="search"
      />
      <el-date-picker
        v-model="filters.startDate"
        type="date"
        value-format="YYYY-MM-DD"
        aria-label="开始日期"
      />
      <el-date-picker
        v-model="filters.endDate"
        type="date"
        value-format="YYYY-MM-DD"
        aria-label="结束日期"
      />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </div>
    <PageState
      :loading="loading"
      :error="errorText"
      :empty="!loading && errorText === '' && entries.length === 0"
      empty-text="输入主体标识后查询成本明细"
      @retry="load"
    >
      <el-descriptions v-if="summary" :column="3" border class="summary">
        <el-descriptions-item label="归集金额"
          ><MoneyText :value="summary.totalAmount"
        /></el-descriptions-item>
        <el-descriptions-item label="完整分摊">{{
          summary.fullyAllocated ? '是' : '否'
        }}</el-descriptions-item>
        <el-descriptions-item label="口径">材料 + 实际人工 + 售后 + 经营分摊</el-descriptions-item>
      </el-descriptions>
      <el-table :data="entries" border empty-text="暂无成本明细">
        <el-table-column prop="costEntryBusinessId" label="成本事实" min-width="200" />
        <el-table-column label="分类" width="150">
          <template #default="{ row }"
            ><CodeTag category="finance.costCategory" :code="row.costCategory"
          /></template>
        </el-table-column>
        <el-table-column prop="businessDate" label="业务日期" width="120" />
        <el-table-column label="操作" width="110">
          <template #default="{ row }"
            ><CodeTag category="finance.costOperation" :code="row.operationType"
          /></template>
        </el-table-column>
        <el-table-column label="金额" width="130">
          <template #default="{ row }"><MoneyText :value="row.amount" /></template>
        </el-table-column>
        <el-table-column label="已分摊" width="90">
          <template #default="{ row }">{{ row.allocated ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="发生时间" width="230">
          <template #default="{ row }"><DateTimeText :value="row.occurredAt" /></template>
        </el-table-column>
      </el-table>
    </PageState>
    <p class="hint">成本合计不是现金支出；参考工资与处理金额不自动成为成本。</p>
  </WorkspacePage>
</template>

<style scoped>
h1 {
  margin: 0 0 12px;
  font-size: 18px;
}
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.subject-type {
  width: 110px;
}
.subject {
  width: 260px;
}
.summary {
  margin-bottom: 12px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.alert {
  margin-bottom: 12px;
}
</style>
