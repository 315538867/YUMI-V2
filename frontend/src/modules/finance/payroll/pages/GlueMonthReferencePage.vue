<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { isErrorCode } from '@/platform/services/errorMessage'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import { useListQuery } from '@/platform/composables/useListQuery'
import type { EmployeeView } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import type { GlueMonthReferenceView, GluePolicyView } from '@/modules/finance/payroll/contracts'
import { payrollService } from '@/modules/finance/payroll/services/payrollService'
import CodeTag from '@/shared/components/CodeTag.vue'
import PageState from '@/shared/components/PageState.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'
import PageShell from '@/shared/components/PageShell.vue'

const policy = ref<GluePolicyView | null>(null)
const employees = ref<EmployeeView[]>([])
const reference = ref<GlueMonthReferenceView | null>(null)
const policyMissing = ref(false)
const { loading, errorText, run } = useAsyncLoad(true)
const { loading: saving, run: runAction } = useAsyncAction()
const { filters, sync, search } = useListQuery(
  { month: new Date().toISOString().slice(0, 7), employeeId: '' },
  { paged: false, onSearch: () => void query() },
)
const successText = ref('')
const form = reactive({ exemptWeightGrams: '300.0000', reasonText: '', expectedGuardVersion: 0 })

async function load(): Promise<void> {
  loading.value = true
  await run(async () => {
    employees.value = await workforceService.employees()
    if (!employees.value.some((item) => item.employeeBusinessId === filters.employeeId)) {
      filters.employeeId = employees.value[0]?.employeeBusinessId ?? ''
    }
    await sync()
  }, '人员加载失败')
  await loadPolicy()
  if (filters.employeeId !== '') {
    await query()
  }
  loading.value = false
}

async function loadPolicy(): Promise<void> {
  policyMissing.value = false
  await run(
    async () => {
      policy.value = await payrollService.gluePolicy(filters.month)
      form.exemptWeightGrams = policy.value.exemptWeightGrams
    },
    '胶水设置加载失败',
    {
      handled: (error) => {
        if (isErrorCode(error, 'PAYROLL_GLUE_POLICY_MISSING')) {
          policy.value = null
          policyMissing.value = true
          return true
        }
        return false
      },
    },
  )
}

async function publish(): Promise<void> {
  await runAction(
    () =>
      payrollService.publishGluePolicy({
        effectiveMonth: `${filters.month}-01`,
        exemptWeightGrams: form.exemptWeightGrams,
        reasonText: form.reasonText,
        expectedGuardVersion: Number(form.expectedGuardVersion),
      }),
    {
      success: '胶水设置已发布；历史核定不变',
      fallback: '胶水设置发布失败',
      onSuccess: (published) => {
        policy.value = published
        policyMissing.value = false
        successText.value = '胶水设置已发布；历史核定不变'
      },
    },
  )
}

async function generate(): Promise<void> {
  if (filters.employeeId === '') {
    errorText.value = '请选择员工'
    return
  }
  await runAction(
    () => payrollService.runGlueMonthReference(filters.employeeId, `${filters.month}-01`),
    {
      success: '自然月胶水参考已生成',
      fallback: '胶水月参考生成失败',
      onSuccess: (generated) => {
        reference.value = generated
        successText.value = '自然月胶水参考已生成'
      },
    },
  )
}

async function query(): Promise<void> {
  if (filters.employeeId === '') {
    reference.value = null
    return
  }
  await run(async () => {
    reference.value = await payrollService.glueMonthReference(filters.employeeId, filters.month)
  }, '胶水月参考读取失败')
}

async function applyMonth(): Promise<void> {
  await sync()
  await loadPolicy()
  if (filters.employeeId !== '') {
    await query()
  }
}

async function applyEmployee(): Promise<void> {
  await search()
}

onMounted(load)
</script>

<template>
  <PageShell title="胶水自然月参考">
    <WorkspacePage class="glue-page">
      <el-alert
        v-if="errorText"
        type="error"
        :closable="false"
        :title="errorText"
        class="yumi-alert"
      />
      <el-alert
        v-if="successText"
        type="success"
        :closable="false"
        :title="successText"
        class="yumi-alert"
      />
      <el-form inline>
        <el-form-item label="月份">
          <el-input
            v-model="filters.month"
            type="month"
            class="month"
            aria-label="月份"
            @change="applyMonth"
          />
        </el-form-item>
        <el-form-item label="每人免扣重量">
          <el-input v-model="form.exemptWeightGrams" class="weight" />
        </el-form-item>
        <el-form-item label="发布原因">
          <el-input v-model="form.reasonText" class="reason" />
        </el-form-item>
        <el-button type="primary" :loading="saving" @click="publish">发布设置</el-button>
      </el-form>
      <p v-if="policy" class="yumi-meta">
        当前版本 v{{ policy.version }}，生效月 {{ policy.effectiveMonth }}，免扣
        {{ policy.exemptWeightGrams }} 克
      </p>
      <el-alert
        v-if="policyMissing"
        type="info"
        :closable="false"
        title="该月尚未配置胶水减免；填写上方表单并发布即可。"
        class="yumi-alert"
      />

      <el-form inline class="employee-row">
        <el-form-item label="员工">
          <el-select
            v-model="filters.employeeId"
            filterable
            class="employee"
            aria-label="员工"
            @change="applyEmployee"
          >
            <el-option
              v-for="employee in employees"
              :key="employee.employeeBusinessId"
              :label="employee.name"
              :value="employee.employeeBusinessId"
            />
          </el-select>
        </el-form-item>
        <el-button @click="query">查询参考</el-button>
        <el-button type="primary" :loading="saving" @click="generate">生成当月参考</el-button>
      </el-form>

      <PageState
        :loading="loading"
        :error="errorText"
        :empty="!loading && errorText === '' && reference === null"
        empty-text="选择员工后查询胶水月参考"
        @retry="query"
      >
        <template v-if="reference">
          <el-descriptions :column="3" border>
            <el-descriptions-item label="状态"
              ><CodeTag category="finance.glueReferenceStatus" :code="reference.status"
            /></el-descriptions-item>
            <el-descriptions-item label="分配重量">{{
              reference.allocatedWeight
            }}</el-descriptions-item>
            <el-descriptions-item label="免扣重量">{{
              reference.exemptWeight
            }}</el-descriptions-item>
            <el-descriptions-item label="不完整原因" :span="2">{{
              reference.incompleteReason ?? '无'
            }}</el-descriptions-item>
            <el-descriptions-item label="是否过期">{{
              reference.stale ? '是' : '否'
            }}</el-descriptions-item>
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
      </PageState>
    </WorkspacePage>
  </PageShell>
</template>

<style scoped>
.month {
  width: 150px;
}
.weight {
  width: 140px;
}
.reason {
  width: 220px;
}
.employee {
  width: 260px;
}
.employee-row {
  margin-top: var(--yumi-space-4);
}
.lines {
  margin-top: var(--yumi-space-3);
}
</style>
