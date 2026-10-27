<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import type { EmployeeView, TeamView } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import type { TargetProcess, TaskView } from '@/modules/production/contracts'
import {
  productionService,
  type AdjustTaskInput,
} from '@/modules/production/services/productionService'
import CodeTag from '@/shared/components/CodeTag.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'

const { loading, errorText, run } = useAsyncLoad(true)
const { loading: saving, run: runAction } = useAsyncAction()

const route = useRoute()
const router = useRouter()
const task = ref<TaskView | null>(null)
const employees = ref<EmployeeView[]>([])
const teams = ref<TeamView[]>([])

const form = reactive({
  subjectType: 'EMPLOYEE' as 'EMPLOYEE' | 'TEAM',
  subjectBusinessId: '',
  workDate: '',
  processCode: null as TargetProcess | null,
  items: [] as Array<{
    taskItemBusinessId: string | null
    productionSourceBusinessId: string
    plannedQuantity: number
  }>,
  enteredStandardMinutes: 60,
  workContent: '',
  relatedProductBusinessId: '',
  plannedQuantity: null as number | null,
  overrideReason: '',
})

const isOtherTask = computed(() => task.value?.taskType === 'OTHER_TASK')
const verifiedItems = computed(
  () => task.value?.items.filter((item) => item.verificationStatus === 'VERIFIED') ?? [],
)
const processLocked = computed(() => verifiedItems.value.length > 0)
const canAddItem = computed(() => task.value?.taskType !== 'REPAIR')
const canRemoveItem = computed(() => task.value?.taskType !== 'REPAIR' || form.items.length > 1)
const activeEmployees = computed(() =>
  employees.value.filter((employee) => employee.employmentStatus === 'EMPLOYED'),
)
const activeTeams = computed(() => teams.value.filter((team) => team.status === 'ACTIVE'))

async function load(): Promise<void> {
  await run(async () => {
    const taskBusinessId = String(route.params.taskBusinessId ?? '')
    const [loadedTask, employeeList, teamList] = await Promise.all([
      productionService.task(taskBusinessId),
      workforceService.employees(),
      workforceService.teams(),
    ])
    task.value = loadedTask
    employees.value = employeeList
    teams.value = teamList
    form.subjectType = loadedTask.subject.subjectType
    form.subjectBusinessId = loadedTask.subject.subjectBusinessId
    form.workDate = loadedTask.workDate
    form.processCode = loadedTask.processCode
    form.items = loadedTask.items
      .filter((item) => item.verificationStatus === 'PENDING')
      .map((item) => ({
        taskItemBusinessId: item.taskItemBusinessId,
        productionSourceBusinessId: item.productionSourceBusinessId ?? '',
        plannedQuantity: item.plannedQuantity,
      }))
    form.enteredStandardMinutes = Math.max(1, Math.round(loadedTask.taskStandardSeconds / 60))
    form.workContent = loadedTask.otherTask?.workContent ?? ''
    form.relatedProductBusinessId = loadedTask.otherTask?.relatedProductBusinessId ?? ''
    form.plannedQuantity = loadedTask.otherTask?.plannedQuantity ?? null
    form.overrideReason = ''
  }, '任务调整加载失败')
}

function changeSubjectType(): void {
  form.subjectBusinessId = ''
}

function addItem(): void {
  form.items.push({
    taskItemBusinessId: null,
    productionSourceBusinessId: '',
    plannedQuantity: 1,
  })
}

function removeItem(index: number): void {
  form.items.splice(index, 1)
}

function validate(): string | null {
  if (form.subjectBusinessId === '') {
    return form.subjectType === 'TEAM' ? '请选择执行小组' : '请选择执行员工'
  }
  if (form.workDate === '') {
    return '请选择工作日期'
  }
  if (isOtherTask.value) {
    if (form.workContent.trim() === '') {
      return '请填写工作内容'
    }
    if (!form.enteredStandardMinutes || form.enteredStandardMinutes <= 0) {
      return '请填写整体标准分钟'
    }
    if (
      form.relatedProductBusinessId.trim() !== '' &&
      (!form.plannedQuantity || form.plannedQuantity <= 0)
    ) {
      return '关联商品时必须填写计划数量'
    }
    return null
  }
  if (form.processCode === null) {
    return '请选择执行工序'
  }
  if (form.items.length === 0) {
    return '至少保留一条未核验明细；如需全部取消请使用任务取消'
  }
  for (const item of form.items) {
    if (item.productionSourceBusinessId.trim() === '') {
      return '每条明细都必须填写生产来源'
    }
    if (!item.plannedQuantity || item.plannedQuantity <= 0) {
      return '明细计划数量必须大于 0'
    }
  }
  return null
}

async function save(): Promise<void> {
  const invalid = validate()
  if (invalid) {
    errorText.value = invalid
    return
  }
  if (!task.value) {
    return
  }
  const input: AdjustTaskInput = {
    subject: {
      subjectType: form.subjectType,
      subjectBusinessId: form.subjectBusinessId,
    },
    workDate: form.workDate,
    processCode: isOtherTask.value ? null : form.processCode,
    items: isOtherTask.value
      ? []
      : form.items.map((item) => ({
          taskItemBusinessId: item.taskItemBusinessId,
          productionSourceBusinessId: item.productionSourceBusinessId.trim(),
          plannedQuantity: Number(item.plannedQuantity),
        })),
    enteredStandardMinutes: isOtherTask.value ? Number(form.enteredStandardMinutes) : null,
    otherTask: isOtherTask.value
      ? {
          workContent: form.workContent.trim(),
          relatedProductBusinessId:
            form.relatedProductBusinessId.trim() === ''
              ? null
              : form.relatedProductBusinessId.trim(),
          plannedQuantity:
            form.relatedProductBusinessId.trim() === '' ? null : Number(form.plannedQuantity),
        }
      : null,
    overrideReason: form.overrideReason.trim() === '' ? null : form.overrideReason.trim(),
  }
  await runAction(() => productionService.adjustTask(task.value!.taskBusinessId, input), {
    success: '任务已调整',
    fallback: '任务调整失败',
    onSuccess: () => router.push(`/production/tasks/${task.value!.taskBusinessId}`),
  })
}

onMounted(load)
</script>

<template>
  <WorkspacePage v-loading="loading" class="adjust-page">
    <header class="head">
      <div>
        <h1>调整任务{{ task ? ` ${task.taskNo}` : '' }}</h1>
        <span class="meta"> 已核验部分保留原日期、主体与标准；未核验部分按本次提交重新占用 </span>
      </div>
      <el-button @click="router.push(`/production/tasks/${task?.taskBusinessId ?? ''}`)">
        返回详情
      </el-button>
    </header>

    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />

    <el-form v-if="task" label-width="120px" class="adjust-form">
      <h2>执行安排</h2>
      <el-form-item label="执行主体">
        <div class="subject-row">
          <el-select v-model="form.subjectType" class="subject-type" @change="changeSubjectType">
            <el-option label="员工" value="EMPLOYEE" />
            <el-option label="小组" value="TEAM" />
          </el-select>
          <el-select
            v-if="form.subjectType === 'EMPLOYEE'"
            v-model="form.subjectBusinessId"
            filterable
            placeholder="选择员工"
          >
            <el-option
              v-for="employee in activeEmployees"
              :key="employee.employeeBusinessId"
              :label="`${employee.name}（${employee.employeeNo}）`"
              :value="employee.employeeBusinessId"
            />
          </el-select>
          <el-select v-else v-model="form.subjectBusinessId" filterable placeholder="选择小组">
            <el-option
              v-for="team in activeTeams"
              :key="team.teamBusinessId"
              :label="team.name"
              :value="team.teamBusinessId"
            />
          </el-select>
        </div>
      </el-form-item>
      <el-form-item label="工作日期">
        <el-date-picker
          v-model="form.workDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="工作日期"
        />
      </el-form-item>

      <template v-if="!isOtherTask">
        <h2>工序与明细</h2>
        <el-form-item label="执行工序">
          <el-select
            v-model="form.processCode"
            :disabled="processLocked || task.taskType === 'EXTRA_PRODUCTION'"
          >
            <el-option label="制作" value="MAKING" />
            <el-option label="捏毛装袋" value="BAGGING" />
            <el-option label="缝边剪袋" value="SEAM_CUTTING" />
          </el-select>
          <span v-if="processLocked" class="field-note">已有核验事实，工序不可修改</span>
        </el-form-item>

        <el-table :data="form.items" border empty-text="没有可调整的未核验明细">
          <el-table-column label="生产来源" min-width="260">
            <template #default="{ row }">
              <el-input v-model="row.productionSourceBusinessId" placeholder="生产来源标识" />
            </template>
          </el-table-column>
          <el-table-column label="计划数量" width="150">
            <template #default="{ row }">
              <el-input-number v-model="row.plannedQuantity" :min="1" controls-position="right" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ $index }">
              <el-button v-if="canRemoveItem" link type="danger" @click="removeItem($index)">
                移除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button v-if="canAddItem" class="add-item" @click="addItem">增加明细</el-button>

        <template v-if="verifiedItems.length > 0">
          <h2>已核验明细</h2>
          <el-alert
            type="info"
            :closable="false"
            title="已核验明细不可修改或移除，继续保留在原日期、主体和标准版本下"
          />
          <el-table :data="verifiedItems" border class="verified-table">
            <el-table-column prop="productionSourceBusinessId" label="生产来源" min-width="260" />
            <el-table-column prop="plannedQuantity" label="计划数量" width="100" />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <CodeTag category="production.verificationStatus" :code="row.verificationStatus" />
              </template>
            </el-table-column>
          </el-table>
        </template>
      </template>

      <template v-else>
        <h2>其他任务</h2>
        <el-form-item label="工作内容" required>
          <el-input v-model="form.workContent" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="整体标准分钟">
          <el-input-number
            v-model="form.enteredStandardMinutes"
            :min="1"
            controls-position="right"
          />
        </el-form-item>
        <el-form-item label="关联商品">
          <el-input
            v-model="form.relatedProductBusinessId"
            placeholder="可选，填写商品标识后按件计酬"
          />
        </el-form-item>
        <el-form-item v-if="form.relatedProductBusinessId.trim() !== ''" label="计划数量">
          <el-input-number v-model="form.plannedQuantity" :min="1" controls-position="right" />
        </el-form-item>
      </template>

      <el-form-item label="超量原因">
        <el-input v-model="form.overrideReason" placeholder="仅超出标准负载时填写" />
      </el-form-item>

      <div class="footer">
        <el-button @click="router.push(`/production/tasks/${task.taskBusinessId}`)">
          取消
        </el-button>
        <el-button type="primary" :loading="saving" @click="save">保存调整</el-button>
      </div>
    </el-form>
  </WorkspacePage>
</template>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 16px;
}

h1 {
  margin: 0 0 4px;
  font-size: 20px;
}

.meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

h2 {
  margin: 22px 0 10px;
  font-size: 16px;
}

.alert {
  margin-bottom: 12px;
}

.adjust-form {
  max-width: 920px;
}

.subject-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.subject-type {
  width: 110px;
  flex: none;
}

.field-note {
  margin-left: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.add-item {
  margin-top: 10px;
}

.verified-table {
  margin-top: 10px;
}

.footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 24px;
}
</style>
