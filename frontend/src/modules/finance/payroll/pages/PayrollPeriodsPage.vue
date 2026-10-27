<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import type { EmployeePayrollView, PayrollPeriodRef } from '@/modules/finance/payroll/contracts'
import { payrollService } from '@/modules/finance/payroll/services/payrollService'
import CodeTag from '@/shared/components/CodeTag.vue'

const router = useRouter()
const periods = ref<PayrollPeriodRef[]>([])
const selected = ref('')
const results = ref<EmployeePayrollView[]>([])
const loading = ref(false)
const errorText = ref('')
const successText = ref('')
const dialogVisible = ref(false)
const form = reactive({
  periodNo: '',
  startDate: new Date().toISOString().slice(0, 8) + '01',
  endDate: new Date().toISOString().slice(0, 10),
})

async function load(): Promise<void> {
  loading.value = true
  try {
    periods.value = await payrollService.periods()
    if (selected.value === '' && periods.value.length > 0) {
      selected.value = periods.value[periods.value.length - 1]!.periodBusinessId
    }
    if (selected.value !== '') {
      results.value = await payrollService.results(selected.value)
    }
  } catch (error) {
    errorText.value = userMessageOf(error, '工资期间加载失败')
  } finally {
    loading.value = false
  }
}

async function createPeriod(): Promise<void> {
  try {
    const period = await payrollService.createPeriod({ ...form })
    dialogVisible.value = false
    selected.value = period.periodBusinessId
    ElMessage.success('工资期间已创建')
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '工资期间创建失败')
  }
}

async function ingestAndCalculate(): Promise<void> {
  if (selected.value === '') {
    errorText.value = '请先选择工资期间'
    return
  }
  try {
    await payrollService.ingestSources()
    const calculation = await payrollService.calculate(selected.value)
    successText.value = `参考已刷新：${calculation.employeeCount} 人 / ${calculation.referenceLineCount} 行`
    ElMessage.success(successText.value)
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '工资参考计算失败')
  }
}

function openEmployee(row: EmployeePayrollView): void {
  void router.push(
    `/payroll/employees?period=${selected.value}&employee=${row.employeeBusinessId}`,
  )
}

onMounted(load)
</script>

<template>
  <section v-loading="loading" class="periods-page">
    <div class="toolbar">
      <el-select v-model="selected" placeholder="选择工资期间" class="period" @change="load">
        <el-option
          v-for="period in periods"
          :key="period.periodBusinessId"
          :label="`${period.periodNo}（${period.startDate} ~ ${period.endDate}）`"
          :value="period.periodBusinessId"
        />
      </el-select>
      <el-button @click="dialogVisible = true">新建期间</el-button>
      <el-button type="primary" @click="ingestAndCalculate">刷新参考</el-button>
      <el-button @click="router.push(`/payroll/payments?period=${selected}`)">付款</el-button>
      <el-button @click="router.push('/payroll/glue')">胶水月参考</el-button>
    </div>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-alert v-if="successText" type="success" :closable="false" :title="successText" class="alert" />
    <el-table :data="results" empty-text="该期间暂无工资结果；请先刷新参考" border>
      <el-table-column prop="employeeBusinessId" label="员工" min-width="200" />
      <el-table-column label="状态" width="130">
        <template #default="{ row }"><CodeTag category="finance.payrollResultStatus" :code="row.status" /></template>
      </el-table-column>
      <el-table-column label="参考行" width="100">
        <template #default="{ row }">{{ row.lines.length }}</template>
      </el-table-column>
      <el-table-column label="实际核定" width="130">
        <template #default="{ row }">{{ row.confirmedAmount?.amount ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="应付" width="130">
        <template #default="{ row }">{{ row.payableAmount?.amount ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEmployee(row)">逐人核对</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="新建工资期间" width="440px">
      <el-form label-width="92px">
        <el-form-item label="期间编号"><el-input v-model="form.periodNo" /></el-form-item>
        <el-form-item label="开始日期">
          <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="结束日期">
          <el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="createPeriod">创建</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.periods-page { background: #fff; border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 16px; }
.toolbar { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 12px; }
.period { width: 320px; }
.alert { margin-bottom: 12px; }
</style>
