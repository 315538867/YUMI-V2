<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import type {
  AssignmentSubjectCurrentView,
  EmployeeView,
  TeamView,
  WorkforceActivityReferenceView,
} from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import CodeLabel from '@/shared/components/CodeLabel.vue'
import CodeTag from '@/shared/components/CodeTag.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'

const { errorText, run } = useAsyncLoad()

const employees = ref<EmployeeView[]>([])
const teams = ref<TeamView[]>([])
const current = ref<AssignmentSubjectCurrentView | null>(null)
const references = ref<WorkforceActivityReferenceView[]>([])
const form = reactive<{ subjectType: 'EMPLOYEE' | 'TEAM'; businessId: string }>({
  subjectType: 'EMPLOYEE',
  businessId: '',
})

async function load(): Promise<void> {
  await run(async () => {
    ;[employees.value, teams.value] = await Promise.all([
      workforceService.employees(),
      workforceService.teams(),
    ])
  }, '主体列表加载失败')
}

async function query(): Promise<void> {
  if (form.businessId === '') {
    errorText.value = '请选择执行主体'
    return
  }
  current.value = null
  references.value = []
  await run(async () => {
    const [subject, activeReferences] = await Promise.all([
      workforceService.subject(form.subjectType, form.businessId),
      workforceService.subjectReferences(form.subjectType, form.businessId),
    ])
    current.value = subject
    references.value = activeReferences
  }, '执行主体查询失败')
}

onMounted(load)
</script>

<template>
  <WorkspacePage class="activity-page">
    <h1>活动引用</h1>
    <p class="hint">
      活动仍被排班、任务或核验引用时，离职、停用小组和资格撤销会被后端拒绝；本页只读查询当前主体。
    </p>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-form inline>
      <el-form-item label="主体类型">
        <el-select v-model="form.subjectType" class="short" @change="form.businessId = ''">
          <el-option label="员工" value="EMPLOYEE" />
          <el-option label="小组" value="TEAM" />
        </el-select>
      </el-form-item>
      <el-form-item label="主体">
        <el-select v-model="form.businessId" filterable class="wide">
          <el-option
            v-for="employee in form.subjectType === 'EMPLOYEE' ? employees : teams"
            :key="
              'employeeBusinessId' in employee
                ? employee.employeeBusinessId
                : employee.teamBusinessId
            "
            :label="employee.name"
            :value="
              'employeeBusinessId' in employee
                ? employee.employeeBusinessId
                : employee.teamBusinessId
            "
          />
        </el-select>
      </el-form-item>
      <el-button type="primary" @click="query">查询</el-button>
    </el-form>

    <el-descriptions v-if="current" :column="2" border class="result">
      <el-descriptions-item label="名称">{{ current.name }}</el-descriptions-item>
      <el-descriptions-item label="内容版本">{{ current.contentVersion }}</el-descriptions-item>
      <el-descriptions-item label="资格" :span="2">
        <span v-for="item in current.qualifications" :key="item.workTypeCode" class="tag">
          <CodeTag category="workforce.workType" :code="item.workTypeCode" />
          <CodeTag category="workforce.qualificationStatus" :code="item.status" />
        </span>
      </el-descriptions-item>
      <el-descriptions-item label="成员" :span="2">
        <el-tag v-for="id in current.memberEmployeeBusinessIds" :key="id" class="tag">
          {{ id }}
        </el-tag>
      </el-descriptions-item>
    </el-descriptions>

    <h2>活动任务引用（{{ references.length }}）</h2>
    <el-table :data="references" border empty-text="当前没有活动引用" size="small">
      <el-table-column label="来源模块" width="130">
        <template #default="{ row }">
          <CodeLabel
            category="workforce.activitySourceModule"
            :code="row.sourceModule"
            fallback="其他模块"
          />
        </template>
      </el-table-column>
      <el-table-column label="来源类型" width="150">
        <template #default="{ row }">
          <CodeLabel
            category="workforce.activitySourceType"
            :code="row.sourceType"
            fallback="其他来源"
          />
        </template>
      </el-table-column>
      <el-table-column prop="sourceBusinessId" label="来源标识" min-width="220" />
      <el-table-column label="工作日期" width="120">
        <template #default="{ row }">{{ row.workDate || '—' }}</template>
      </el-table-column>
      <el-table-column label="工种" width="130">
        <template #default="{ row }">
          <CodeTag v-if="row.workTypeCode" category="workforce.workType" :code="row.workTypeCode" />
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <CodeTag category="workforce.reservationStatus" :code="row.status" />
        </template>
      </el-table-column>
    </el-table>
  </WorkspacePage>
</template>

<style scoped>
h1 {
  margin: 0 0 4px;
  font-size: 18px;
}

.hint {
  margin: 0 0 16px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.short {
  width: 120px;
}

.wide {
  width: 240px;
}

.tag {
  margin-right: 4px;
}

.result {
  margin-top: 16px;
}

h2 {
  margin: 20px 0 10px;
  font-size: 16px;
}

.alert {
  margin-bottom: 12px;
}
</style>
