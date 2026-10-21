<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { userMessageOf } from '@/platform/services/errorMessage'
import type { CostEntryView, CostSubjectSummary } from '@/modules/finance/costledger/contracts'
import { costLedgerService } from '@/modules/finance/costledger/services/costLedgerService'

const filters = reactive({
  subjectType: 'ORDER',
  subjectId: '',
  startDate: new Date().toISOString().slice(0, 8) + '01',
  endDate: new Date().toISOString().slice(0, 10),
})
const entries = ref<CostEntryView[]>([])
const summary = ref<CostSubjectSummary | null>(null)
const errorText = ref('')

async function load(): Promise<void> {
  if (filters.subjectId.trim() === '') {
    errorText.value = '请输入主体标识'
    return
  }
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
  }
}

onMounted(load)
</script>

<template>
  <section class="entries-page">
    <h1>成本明细与汇总</h1>
    <div class="toolbar">
      <el-select v-model="filters.subjectType" class="subject-type">
        <el-option label="订单" value="ORDER" />
        <el-option label="员工" value="EMPLOYEE" />
        <el-option label="商品" value="PRODUCT" />
      </el-select>
      <el-input v-model="filters.subjectId" placeholder="主体标识" class="subject" />
      <el-date-picker v-model="filters.startDate" type="date" value-format="YYYY-MM-DD" />
      <el-date-picker v-model="filters.endDate" type="date" value-format="YYYY-MM-DD" />
      <el-button type="primary" @click="load">查询</el-button>
    </div>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-descriptions v-if="summary" :column="3" border class="summary">
      <el-descriptions-item label="归集金额">{{ summary.totalAmount }}</el-descriptions-item>
      <el-descriptions-item label="完整分摊">{{ summary.fullyAllocated ? '是' : '否' }}</el-descriptions-item>
      <el-descriptions-item label="口径">材料 + 实际人工 + 售后 + 经营分摊</el-descriptions-item>
    </el-descriptions>
    <el-table :data="entries" border empty-text="暂无成本明细">
      <el-table-column prop="costEntryBusinessId" label="成本事实" min-width="200" />
      <el-table-column prop="costCategory" label="分类" width="150" />
      <el-table-column prop="businessDate" label="业务日期" width="120" />
      <el-table-column prop="operationType" label="操作" width="110" />
      <el-table-column prop="amount" label="金额" width="130" />
      <el-table-column label="已分摊" width="90">
        <template #default="{ row }">{{ row.allocated ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column prop="occurredAt" label="发生时间" width="230" />
    </el-table>
    <p class="hint">成本合计不是现金支出；参考工资与处理金额不自动成为成本。</p>
  </section>
</template>

<style scoped>
.entries-page { background: #fff; border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 16px; }
h1 { margin: 0 0 12px; font-size: 18px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.subject-type { width: 110px; }
.subject { width: 260px; }
.summary { margin-bottom: 12px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; }
.alert { margin-bottom: 12px; }
</style>
