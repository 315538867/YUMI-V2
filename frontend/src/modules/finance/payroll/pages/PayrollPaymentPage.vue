<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import type { EmployeePayrollView, PayrollPeriodRef } from '@/modules/finance/payroll/contracts'
import { payrollService } from '@/modules/finance/payroll/services/payrollService'
import { cashService } from '@/modules/finance/cash/services/cashService'
import CodeTag from '@/shared/components/CodeTag.vue'
import MoneyText from '@/shared/components/MoneyText.vue'

const route = useRoute()
const periods = ref<PayrollPeriodRef[]>([])
const selected = ref(typeof route.query.period === 'string' ? route.query.period : '')
const results = ref<EmployeePayrollView[]>([])
const selectedRows = ref<EmployeePayrollView[]>([])
const amounts = reactive<Record<string, string>>({})
const businessDate = ref(new Date().toISOString().slice(0, 10))
const errorText = ref('')
const successText = ref('')

async function load(): Promise<void> {
  try {
    periods.value = await payrollService.periods()
    if (selected.value === '' && periods.value.length > 0) {
      selected.value = periods.value[periods.value.length - 1]!.periodBusinessId
    }
    if (selected.value !== '') {
      results.value = await payrollService.results(selected.value)
      for (const row of results.value) {
        amounts[row.resultBusinessId] = row.payableAmount?.amount ?? ''
      }
    }
  } catch (error) {
    errorText.value = userMessageOf(error, '工资付款数据加载失败')
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
      <el-select v-model="selected" class="period" @change="load">
        <el-option v-for="period in periods" :key="period.periodBusinessId" :label="period.periodNo" :value="period.periodBusinessId" />
      </el-select>
      <el-date-picker v-model="businessDate" type="date" value-format="YYYY-MM-DD" />
      <el-button type="primary" @click="pay">按选中员工付款</el-button>
    </div>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-alert v-if="successText" type="success" :closable="false" :title="successText" class="alert" />
    <el-table :data="results" border @selection-change="selectionChanged">
      <el-table-column type="selection" width="50" />
      <el-table-column prop="employeeBusinessId" label="员工" min-width="200" />
      <el-table-column label="状态" width="130">
        <template #default="{ row }"><CodeTag category="finance.payrollResultStatus" :code="row.status" /></template>
      </el-table-column>
      <el-table-column label="应付" width="130">
        <template #default="{ row }"><MoneyText :value="row.payableAmount" /></template>
      </el-table-column>
      <el-table-column label="本次付款" width="160">
        <template #default="{ row }">
          <el-input v-model="amounts[row.resultBusinessId]" :disabled="row.status !== 'CONFIRMED'" />
        </template>
      </el-table-column>
    </el-table>
    <p class="hint">只能选择已核定员工付款；部分付款与多人付款由 cash 在同一事务校验上限。</p>
  </section>
</template>

<style scoped>
.payment-page h1 { margin: 0 0 12px; font-size: 18px; }

.payment-page { background: #fff; border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 16px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.period { width: 240px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; }
.alert { margin-bottom: 12px; }
</style>
