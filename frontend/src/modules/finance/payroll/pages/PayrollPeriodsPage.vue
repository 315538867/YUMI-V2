<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useListQuery } from '@/platform/composables/useListQuery'
import type { EmployeePayrollView, PayrollPeriodRef } from '@/modules/finance/payroll/contracts'
import { payrollService } from '@/modules/finance/payroll/services/payrollService'
import CodeTag from '@/shared/components/CodeTag.vue'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import ListPage from '@/shared/components/ListPage.vue'
import MoneyText from '@/shared/components/MoneyText.vue'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'

const { loading, errorText, run } = useAsyncLoad()

const router = useRouter()
const periods = ref<PayrollPeriodRef[]>([])
const results = ref<EmployeePayrollView[]>([])
const successText = ref('')
const { loading: saving, run: runAction } = useAsyncAction()
const dialogVisible = ref(false)
const { filters, sync, search } = useListQuery(
  { period: '' },
  { paged: false, onSearch: () => void load() },
)
const form = reactive({
  periodNo: '',
  startDate: new Date().toISOString().slice(0, 8) + '01',
  endDate: new Date().toISOString().slice(0, 10),
})

async function load(): Promise<void> {
  await run(async () => {
    periods.value = await payrollService.periods()
    if (!periods.value.some((item) => item.periodBusinessId === filters.period)) {
      filters.period = periods.value[periods.value.length - 1]?.periodBusinessId ?? ''
    }
    await sync()
    if (filters.period !== '') {
      results.value = await payrollService.results(filters.period)
    }
  }, '工资期间加载失败')
}

async function createPeriod(): Promise<void> {
  await runAction(() => payrollService.createPeriod({ ...form }), {
    success: '工资期间已创建',
    fallback: '工资期间创建失败',
    onSuccess: async (period) => {
      dialogVisible.value = false
      filters.period = period.periodBusinessId
      await sync()
      await load()
    },
  })
}

async function ingestAndCalculate(): Promise<void> {
  if (filters.period === '') {
    errorText.value = '请先选择工资期间'
    return
  }
  await runAction(
    async () => {
      await payrollService.ingestSources()
      return payrollService.calculate(filters.period)
    },
    {
      success: '参考已刷新',
      fallback: '工资参考计算失败',
      onSuccess: async (calculation) => {
        successText.value = `参考已刷新：${calculation.employeeCount} 人 / ${calculation.referenceLineCount} 行`
        await load()
      },
    },
  )
}

function openEmployee(row: EmployeePayrollView): void {
  void router.push(`/payroll/employees?period=${filters.period}&employee=${row.employeeBusinessId}`)
}

onMounted(load)
</script>

<template>
  <ListPage
    class="periods-page"
    title="工资期间"
    :loading="loading"
    :error="errorText"
    @retry="load"
  >
    <template #filters>
      <el-select
        v-model="filters.period"
        placeholder="选择工资期间"
        class="period"
        aria-label="工资期间"
        @change="search"
      >
        <el-option
          v-for="period in periods"
          :key="period.periodBusinessId"
          :label="`${period.periodNo}（${period.startDate} ~ ${period.endDate}）`"
          :value="period.periodBusinessId"
        />
      </el-select>
    </template>
    <template #actions>
      <el-button @click="dialogVisible = true">新建期间</el-button>
      <el-button type="primary" :loading="saving" @click="ingestAndCalculate">刷新参考</el-button>
      <el-button @click="router.push(`/payroll/payments?period=${filters.period}`)">付款</el-button>
      <el-button @click="router.push('/payroll/glue')">胶水月参考</el-button>
    </template>
    <template #notice>
      <el-alert v-if="successText" type="success" :closable="false" :title="successText" />
    </template>
    <el-table :data="results" empty-text="该期间暂无工资结果；请先刷新参考" border>
      <el-table-column prop="employeeBusinessId" label="员工" min-width="200" />
      <el-table-column label="状态" width="130">
        <template #default="{ row }"
          ><CodeTag category="finance.payrollResultStatus" :code="row.status"
        /></template>
      </el-table-column>
      <el-table-column label="参考行" width="100">
        <template #default="{ row }">{{ row.lines.length }}</template>
      </el-table-column>
      <el-table-column label="实际核定" width="130">
        <template #default="{ row }"><MoneyText :value="row.confirmedAmount" /></template>
      </el-table-column>
      <el-table-column label="应付" width="130">
        <template #default="{ row }"><MoneyText :value="row.payableAmount" /></template>
      </el-table-column>
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEmployee(row)">逐人核对</el-button>
        </template>
      </el-table-column>
    </el-table>
  </ListPage>

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
      <el-button type="primary" :loading="saving" @click="createPeriod">创建</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.period {
  width: 320px;
}
</style>
