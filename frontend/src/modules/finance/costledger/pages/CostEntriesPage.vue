<script setup lang="ts">
import { ref } from 'vue'
import { useListQuery } from '@/platform/composables/useListQuery'
import type { CostEntryView, CostSubjectSummary } from '@/modules/finance/costledger/contracts'
import { costLedgerService } from '@/modules/finance/costledger/services/costLedgerService'
import CodeTag from '@/shared/components/CodeTag.vue'
import DateTimeText from '@/shared/components/DateTimeText.vue'
import ListPage from '@/shared/components/ListPage.vue'
import MoneyText from '@/shared/components/MoneyText.vue'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'

const { loading, errorText, run } = useAsyncLoad()

const entries = ref<CostEntryView[]>([])
const summary = ref<CostSubjectSummary | null>(null)
const detailVisible = ref(false)
const detailEntry = ref<CostEntryView | null>(null)
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
  await run(async () => {
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
  }, '成本明细加载失败')
}

function openDetail(row: CostEntryView): void {
  detailEntry.value = row
  detailVisible.value = true
}

void load()
</script>

<template>
  <ListPage
    class="entries-page"
    title="成本明细与汇总"
    :loading="loading"
    :error="errorText"
    :empty="!loading && errorText === '' && entries.length === 0"
    empty-text="输入主体标识后查询成本明细"
    @retry="load"
  >
    <template #filters>
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
    </template>
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
      <el-table-column label="操作" width="80" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>
    <template #footer>
      <p class="yumi-hint">成本合计不是现金支出；参考工资与处理金额不自动成为成本。</p>
    </template>
  </ListPage>

  <el-drawer v-model="detailVisible" title="成本记录详情" size="420px">
    <el-descriptions v-if="detailEntry" :column="1" border>
      <el-descriptions-item label="成本事实">
        {{ detailEntry.costEntryBusinessId }}
      </el-descriptions-item>
      <el-descriptions-item label="分类">
        <CodeTag category="finance.costCategory" :code="detailEntry.costCategory" />
      </el-descriptions-item>
      <el-descriptions-item label="主体">
        <CodeTag category="finance.costSubjectType" :code="detailEntry.subjectType" />
        · {{ detailEntry.subjectBusinessId }}
      </el-descriptions-item>
      <el-descriptions-item label="金额">
        <MoneyText :value="detailEntry.amount" />
      </el-descriptions-item>
      <el-descriptions-item label="业务日期">{{ detailEntry.businessDate }}</el-descriptions-item>
      <el-descriptions-item label="操作">
        <CodeTag category="finance.costOperation" :code="detailEntry.operationType" />
      </el-descriptions-item>
      <el-descriptions-item label="已分摊">
        {{ detailEntry.allocated ? '是' : '否' }}
      </el-descriptions-item>
      <el-descriptions-item label="发生时间">
        <DateTimeText :value="detailEntry.occurredAt" />
      </el-descriptions-item>
    </el-descriptions>
  </el-drawer>
</template>

<style scoped>
.subject-type {
  width: 110px;
}
.subject {
  width: 260px;
}
.summary {
  margin-bottom: var(--yumi-space-3);
}
</style>
