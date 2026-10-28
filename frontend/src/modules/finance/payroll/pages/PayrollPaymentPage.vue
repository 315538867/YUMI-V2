<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useListQuery } from '@/platform/composables/useListQuery'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import type { EmployeePayrollView, PayrollPeriodRef } from '@/modules/finance/payroll/contracts'
import { payrollService } from '@/modules/finance/payroll/services/payrollService'
import { cashService } from '@/modules/finance/cash/services/cashService'
import CodeTag from '@/shared/components/CodeTag.vue'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import ListPage from '@/shared/components/ListPage.vue'
import MoneyText from '@/shared/components/MoneyText.vue'
import BusinessFileUpload from '@/shared/components/BusinessFileUpload.vue'

const { loading, errorText, run } = useAsyncLoad()

const periods = ref<PayrollPeriodRef[]>([])
const results = ref<EmployeePayrollView[]>([])
const selectedRows = ref<EmployeePayrollView[]>([])
const amounts = reactive<Record<string, string>>({})
const businessDate = ref(new Date().toISOString().slice(0, 10))
const successText = ref('')
const voucherId = ref<string | null>(null)
const { loading: saving, run: runAction } = useAsyncAction()
const { filters, sync, search } = useListQuery(
  { period: '' },
  { paged: false, onSearch: () => void load() },
)

async function load(): Promise<void> {
  await run(async () => {
    periods.value = await payrollService.periods()
    if (!periods.value.some((item) => item.periodBusinessId === filters.period)) {
      filters.period = periods.value[periods.value.length - 1]?.periodBusinessId ?? ''
    }
    await sync()
    if (filters.period !== '') {
      results.value = await payrollService.results(filters.period)
      for (const row of results.value) {
        amounts[row.resultBusinessId] = row.remainingAmount?.amount ?? ''
      }
    }
  }, '工资付款数据加载失败')
}

function selectionChanged(rows: EmployeePayrollView[]): void {
  selectedRows.value = rows
}

async function pay(): Promise<void> {
  if (selectedRows.value.length === 0) {
    errorText.value = '请选择已核定员工'
    return
  }
  await runAction(
    () =>
      cashService.payPayroll(
        selectedRows.value.map((row) => ({
          resultBusinessId: row.resultBusinessId,
          amount: amounts[row.resultBusinessId] ?? '0.00',
        })),
        businessDate.value,
        voucherId.value === null ? [] : [voucherId.value],
      ),
    {
      success: '工资付款已登记到现金；未核定员工不会被支付',
      fallback: '工资付款失败',
      onSuccess: async () => {
        successText.value = '工资付款已登记到现金；未核定员工不会被支付'
        voucherId.value = null
        await load()
      },
    },
  )
}

onMounted(load)
</script>

<template>
  <ListPage
    class="payment-page"
    title="工资付款"
    :heading="false"
    panel-title="工资付款"
    panel-hint="只能选择已核定员工；部分付款与多人付款分别校验上限"
    :loading="loading"
    :error="errorText"
    @retry="load"
  >
    <template #filters>
      <el-select v-model="filters.period" class="period" aria-label="工资期间" @change="search">
        <el-option
          v-for="period in periods"
          :key="period.periodBusinessId"
          :label="period.periodNo"
          :value="period.periodBusinessId"
        />
      </el-select>
      <el-date-picker
        v-model="businessDate"
        type="date"
        value-format="YYYY-MM-DD"
        aria-label="付款日期"
        :disabled-date="(date: Date) => date.getTime() > Date.now()"
      />
    </template>
    <template #panel-actions>
      <el-button type="primary" :loading="saving" @click="pay">按选中员工付款</el-button>
    </template>
    <template #notice>
      <BusinessFileUpload
        v-model="voucherId"
        permission-code="finance.read"
        label="上传付款凭证（可选）"
      />
      <el-alert v-if="successText" type="success" :closable="false" :title="successText" />
    </template>
    <el-table :data="results" border @selection-change="selectionChanged">
      <el-table-column
        type="selection"
        width="50"
        :selectable="(row: EmployeePayrollView) => row.status === 'CONFIRMED'"
      />
      <el-table-column label="员工" min-width="220">
        <template #default="{ row }">
          <span class="yumi-cell-title">{{ row.employeeName ?? row.employeeBusinessId }}</span>
          <span v-if="row.employeeNo" class="yumi-cell-sub">{{ row.employeeNo }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="130">
        <template #default="{ row }"
          ><CodeTag category="finance.payrollResultStatus" :code="row.status"
        /></template>
      </el-table-column>
      <el-table-column label="应付" width="130">
        <template #default="{ row }"><MoneyText :value="row.payableAmount" /></template>
      </el-table-column>
      <el-table-column label="已付 / 待付" width="170">
        <template #default="{ row }">
          <MoneyText :value="row.paidAmount" /> /
          <MoneyText :value="row.remainingAmount" />
        </template>
      </el-table-column>
      <el-table-column label="本次付款" width="160">
        <template #default="{ row }">
          <el-input
            v-model="amounts[row.resultBusinessId]"
            :disabled="row.status !== 'CONFIRMED'"
            :aria-label="`${row.employeeName ?? row.employeeBusinessId} 本次付款`"
          />
        </template>
      </el-table-column>
    </el-table>
    <template #footer>
      <p class="yumi-hint">
        只能选择已核定员工付款；部分付款与多人付款由 cash 在同一事务校验上限。
      </p>
    </template>
  </ListPage>
</template>

<style scoped>
.period {
  width: 240px;
}
</style>
