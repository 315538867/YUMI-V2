<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import type { EmployeeView } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import type { GlueMonthReferenceView, GluePolicyView } from '@/modules/finance/payroll/contracts'
import { payrollService } from '@/modules/finance/payroll/services/payrollService'

const month = ref(new Date().toISOString().slice(0, 7))
const policy = ref<GluePolicyView | null>(null)
const employees = ref<EmployeeView[]>([])
const employeeId = ref('')
const reference = ref<GlueMonthReferenceView | null>(null)
const errorText = ref('')
const successText = ref('')
const form = reactive({ exemptWeightGrams: '300.0000', reasonText: '', expectedGuardVersion: 0 })

async function load(): Promise<void> {
  try {
    employees.value = await workforceService.employees()
    employeeId.value = employees.value[0]?.employeeBusinessId ?? ''
  } catch (error) {
    errorText.value = userMessageOf(error, '人员加载失败')
  }
  await loadPolicy()
}

async function loadPolicy(): Promise<void> {
  try {
    policy.value = await payrollService.gluePolicy(month.value)
    form.exemptWeightGrams = policy.value.exemptWeightGrams
  } catch {
    policy.value = null
  }
}

async function publish(): Promise<void> {
  try {
    policy.value = await payrollService.publishGluePolicy({
      effectiveMonth: `${month.value}-01`,
      exemptWeightGrams: form.exemptWeightGrams,
      reasonText: form.reasonText,
      expectedGuardVersion: Number(form.expectedGuardVersion),
    })
    successText.value = '胶水设置已发布；历史核定不变'
    ElMessage.success(successText.value)
  } catch (error) {
    errorText.value = userMessageOf(error, '胶水设置发布失败')
  }
}

async function run(): Promise<void> {
  if (employeeId.value === '') {
    errorText.value = '请选择员工'
    return
  }
  try {
    reference.value = await payrollService.runGlueMonthReference(
      employeeId.value,
      `${month.value}-01`,
    )
    successText.value = '自然月胶水参考已生成'
    ElMessage.success(successText.value)
  } catch (error) {
    errorText.value = userMessageOf(error, '胶水月参考生成失败')
  }
}

async function query(): Promise<void> {
  if (employeeId.value === '') {
    return
  }
  try {
    reference.value = await payrollService.glueMonthReference(employeeId.value, month.value)
  } catch (error) {
    errorText.value = userMessageOf(error, '胶水月参考读取失败')
  }
}

onMounted(load)
</script>

<template>
  <section class="glue-page">
    <h1>胶水自然月参考</h1>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-alert v-if="successText" type="success" :closable="false" :title="successText" class="alert" />
    <el-form inline>
      <el-form-item label="月份">
        <el-input v-model="month" type="month" class="month" @change="loadPolicy" />
      </el-form-item>
      <el-form-item label="每人免扣重量">
        <el-input v-model="form.exemptWeightGrams" class="weight" />
      </el-form-item>
      <el-form-item label="发布原因">
        <el-input v-model="form.reasonText" class="reason" />
      </el-form-item>
      <el-button type="primary" @click="publish">发布设置</el-button>
    </el-form>
    <p v-if="policy" class="meta">
      当前版本 v{{ policy.version }}，生效月 {{ policy.effectiveMonth }}，免扣 {{ policy.exemptWeightGrams }} 克
    </p>
    <p v-else class="meta">该月尚无胶水设置。</p>

    <el-form inline class="employee-row">
      <el-form-item label="员工">
        <el-select v-model="employeeId" filterable class="employee">
          <el-option v-for="employee in employees" :key="employee.employeeBusinessId" :label="employee.name" :value="employee.employeeBusinessId" />
        </el-select>
      </el-form-item>
      <el-button @click="query">查询参考</el-button>
      <el-button type="primary" @click="run">生成当月参考</el-button>
    </el-form>

    <template v-if="reference">
      <el-descriptions :column="3" border>
        <el-descriptions-item label="状态">{{ reference.status }}</el-descriptions-item>
        <el-descriptions-item label="分配重量">{{ reference.allocatedWeight }}</el-descriptions-item>
        <el-descriptions-item label="免扣重量">{{ reference.exemptWeight }}</el-descriptions-item>
        <el-descriptions-item label="不完整原因" :span="2">{{ reference.incompleteReason ?? '无' }}</el-descriptions-item>
        <el-descriptions-item label="是否过期">{{ reference.stale ? '是' : '否' }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="reference.lines" border size="small" class="lines">
        <el-table-column prop="businessDate" label="日期" width="120" />
        <el-table-column prop="allocatedWeight" label="分配重量" width="120" />
        <el-table-column prop="exemptWeight" label="免扣" width="100" />
        <el-table-column prop="chargeableWeight" label="应扣" width="100" />
        <el-table-column prop="glueUnitPrice" label="单价" width="100" />
        <el-table-column prop="preAmount" label="调整前" width="110" />
        <el-table-column prop="postAmount" label="调整后" width="110" />
      </el-table>
    </template>
  </section>
</template>

<style scoped>
.glue-page { background: #fff; border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 20px; }
h1 { margin: 0 0 12px; font-size: 18px; }
.month { width: 150px; }
.weight { width: 140px; }
.reason { width: 220px; }
.employee { width: 260px; }
.employee-row { margin-top: 16px; }
.meta { color: var(--el-text-color-secondary); font-size: 13px; }
.lines { margin-top: 12px; }
.alert { margin-bottom: 12px; }
</style>
