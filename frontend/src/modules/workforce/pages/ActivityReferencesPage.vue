<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { userMessageOf } from '@/platform/services/errorMessage'
import type {
  AssignmentSubjectCurrentView,
  EmployeeView,
  TeamView,
} from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import CodeTag from '@/shared/components/CodeTag.vue'

const employees = ref<EmployeeView[]>([])
const teams = ref<TeamView[]>([])
const current = ref<AssignmentSubjectCurrentView | null>(null)
const errorText = ref('')
const form = reactive<{ subjectType: 'EMPLOYEE' | 'TEAM'; businessId: string }>({
  subjectType: 'EMPLOYEE',
  businessId: '',
})

async function load(): Promise<void> {
  try {
    ;[employees.value, teams.value] = await Promise.all([
      workforceService.employees(),
      workforceService.teams(),
    ])
  } catch (error) {
    errorText.value = userMessageOf(error, '主体列表加载失败')
  }
}

async function query(): Promise<void> {
  if (form.businessId === '') {
    errorText.value = '请选择执行主体'
    return
  }
  errorText.value = ''
  try {
    current.value = await workforceService.subject(form.subjectType, form.businessId)
  } catch (error) {
    current.value = null
    errorText.value = userMessageOf(error, '执行主体查询失败')
  }
}

onMounted(load)
</script>

<template>
  <section class="activity-page">
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
  </section>
</template>

<style scoped>
.activity-page {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 20px;
}

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

.alert {
  margin-bottom: 12px;
}
</style>
