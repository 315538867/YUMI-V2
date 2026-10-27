<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { useListQuery } from '@/platform/composables/useListQuery'
import type { EmployeePayrollView, PayrollPeriodRef } from '@/modules/finance/payroll/contracts'
import { payrollService } from '@/modules/finance/payroll/services/payrollService'
import { cashService } from '@/modules/finance/cash/services/cashService'
import CodeTag from '@/shared/components/CodeTag.vue'
import MoneyText from '@/shared/components/MoneyText.vue'
import PageState from '@/shared/components/PageState.vue'

const periods = ref<PayrollPeriodRef[]>([])
const results = ref<EmployeePayrollView[]>([])
const loading = ref(false)
const selectedRows = ref<EmployeePayrollView[]>([])
const amounts = reactive<Record<string, string>>({})
const businessDate = ref(new Date().toISOString().slice(0, 10))
const errorText = ref('')
const successText = ref('')
const { filters, sync, search } = useListQuery(
  { period: '' },
  { paged: false, onSearch: () => void load() },
)

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    periods.value = await payrollService.periods()
    if (!periods.value.some((item) => item.periodBusinessId === filters.period)) {
      filters.period = periods.value[periods.value.length - 1]?.periodBusinessId ?? ''
    }
    await sync()
    if (filters.period !== '') {
      results.value = await payrollService.results(filters.period)
      for (const row of results.value) {
        amounts[row.resultBusinessId] = row.payableAmount?.amount ?? ''
      }
    }
  } catch (error) {
    errorText.value = userMessageOf(error, '工资付款数据加载失败')
  } finally {
    loading.value = false
  }
}

function selectionChanged(rows: EmployeePayrollView[]): void {
  selectedRows.value = rows
}

async function pay(): Promise<void> {
  if (selectedRows.value.length === 0) {
    errorText.value = '请选择已核定员工'
    return
  }
  errorText.value = ''
  try {
    await cashService.payPayroll(
      selectedRows.value.map((row) => ({
        resultBusinessId: row.resultBusinessId,
        amount: amounts[row.resultBusinessId] ?? '0.00',
      })),
      businessDate.value,
    )
    successText.value = '工资付款已登记到现金；未核定员工不会被支付'
    ElMessage.success(successText.value)
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '工资付款失败')
  }
}

onMounted(load)
</script>

<template>
  <section class="payment-page">
    <h1>工资付款</h1>
    <div class="toolbar">
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
      />
      <el-button type="primary" @click="pay">按选中员工付款</el-button>
    </div>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-alert
      v-if="successText"
      type="success"
      :closable="false"
      :title="successText"
      class="alert"
    />
    <PageState :loading="loading" :error="errorText" @retry="load">
      <el-table :data="results" border @selection-change="selectionChanged">
        <el-table-column type="selection" width="50" />
        <el-table-column prop="employeeBusinessId" label="员工" min-width="200" />
        <el-table-column label="状态" width="130">
          <template #default="{ row }"
            ><CodeTag category="finance.payrollResultStatus" :code="row.status"
          /></template>
        </el-table-column>
        <el-table-column label="应付" width="130">
          <template #default="{ row }"><MoneyText :value="row.payableAmount" /></template>
        </el-table-column>
        <el-table-column label="本次付款" width="160">
          <template #default="{ row }">
            <el-input
              v-model="amounts[row.resultBusinessId]"
              :disabled="row.status !== 'CONFIRMED'"
              :aria-label="`${row.employeeBusinessId} 本次付款`"
            />
          </template>
        </el-table-column>
      </el-table>
    </PageState>
    <p class="hint">只能选择已核定员工付款；部分付款与多人付款由 cash 在同一事务校验上限。</p>
  </section>
</template>

<style scoped>
.payment-page h1 {
  margin: 0 0 12px;
  font-size: 18px;
}

.payment-page {
  background: var(--yumi-surface);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 16px;
}
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.period {
  width: 240px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.alert {
  margin-bottom: 12px;
}
</style>
