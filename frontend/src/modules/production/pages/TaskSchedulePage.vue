<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { useListQuery } from '@/platform/composables/useListQuery'
import type { EmployeeView } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import type { TargetProcess, TaskType, TaskView } from '@/modules/production/contracts'
import { productionService } from '@/modules/production/services/productionService'
import CodeTag from '@/shared/components/CodeTag.vue'
import PageState from '@/shared/components/PageState.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'

const router = useRouter()
const tasks = ref<TaskView[]>([])
const employees = ref<EmployeeView[]>([])
const loading = ref(false)
const errorText = ref('')
const dialogVisible = ref(false)
const saving = ref(false)
const { filters, search, reset } = useListQuery(
  { workDate: new Date().toISOString().slice(0, 10) },
  { paged: false, onSearch: () => void load() },
)

const form = reactive({
  taskType: 'OTHER_TASK' as TaskType,
  employeeBusinessId: '',
  processCode: 'MAKING' as TargetProcess,
  sourceId: '',
  plannedQuantity: 1,
  enteredStandardMinutes: 60,
  workContent: '',
  overrideReason: '',
})

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    ;[tasks.value, employees.value] = await Promise.all([
      productionService.tasks(filters.workDate),
      workforceService.employees(),
    ])
  } catch (error) {
    errorText.value = userMessageOf(error, '排班加载失败')
  } finally {
    loading.value = false
  }
}

async function cancelTask(task: TaskView): Promise<void> {
  try {
    const result = await ElMessageBox.prompt('请输入取消原因', `取消任务 ${task.taskNo}`, {
      inputValidator: (value) => (value.trim() === '' ? '取消原因不能为空' : true),
    })
    await productionService.cancelTask(task.taskBusinessId, result.value)
    ElMessage.success('任务已取消；未核验计划占用已释放')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      errorText.value = userMessageOf(error, '任务取消失败')
    }
  }
}

function open(): void {
  form.taskType = 'OTHER_TASK'
  form.employeeBusinessId =
    employees.value.find((employee) => employee.employmentStatus === 'EMPLOYED')
      ?.employeeBusinessId ?? ''
  form.sourceId = ''
  form.plannedQuantity = 1
  form.enteredStandardMinutes = 60
  form.workContent = ''
  form.overrideReason = ''
  dialogVisible.value = true
}

async function arrange(): Promise<void> {
  if (form.employeeBusinessId === '') {
    errorText.value = '请选择执行员工'
    return
  }
  saving.value = true
  try {
    if (form.taskType === 'OTHER_TASK') {
      await productionService.arrange({
        taskType: 'OTHER_TASK',
        subject: { subjectType: 'EMPLOYEE', subjectBusinessId: form.employeeBusinessId },
        workDate: filters.workDate,
        processCode: null,
        items: [],
        enteredStandardMinutes: Number(form.enteredStandardMinutes),
        otherTask: {
          workContent: form.workContent,
          relatedProductBusinessId: null,
          plannedQuantity: null,
        },
        overrideReason: form.overrideReason === '' ? null : form.overrideReason,
      })
    } else {
      await productionService.arrange({
        taskType: 'NORMAL_PRODUCTION',
        subject: { subjectType: 'EMPLOYEE', subjectBusinessId: form.employeeBusinessId },
        workDate: filters.workDate,
        processCode: form.processCode,
        items: [
          {
            productionSourceBusinessId: form.sourceId.trim(),
            plannedQuantity: Number(form.plannedQuantity),
          },
        ],
        enteredStandardMinutes: null,
        otherTask: null,
        overrideReason: form.overrideReason === '' ? null : form.overrideReason,
      })
    }
    ElMessage.success('任务已安排')
    dialogVisible.value = false
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '任务安排失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="schedule-page">
    <div class="toolbar">
      <el-date-picker
        v-model="filters.workDate"
        type="date"
        value-format="YYYY-MM-DD"
        placeholder="工作日期"
        aria-label="工作日期"
        @change="search"
      />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <PermissionButton permission="production.schedule" @click="open">安排任务</PermissionButton>
    </div>
    <PageState :loading="loading" :error="errorText" @retry="load">
      <el-table :data="tasks" empty-text="当日暂无任务" border>
        <el-table-column prop="taskNo" label="任务编号" width="140" />
        <el-table-column label="类型" width="150">
          <template #default="{ row }"
            ><CodeTag category="production.taskType" :code="row.taskType"
          /></template>
        </el-table-column>
        <el-table-column label="执行主体" min-width="150">
          <template #default="{ row }">{{ row.subject.subjectBusinessId }}</template>
        </el-table-column>
        <el-table-column label="工序" width="120">
          <template #default="{ row }"
            ><CodeTag category="production.targetProcess" :code="row.processCode"
          /></template>
        </el-table-column>
        <el-table-column prop="plannedWorkQuantity" label="计划量" width="90" />
        <el-table-column prop="taskStandardSeconds" label="标准秒数" width="110" />
        <el-table-column label="状态" width="150">
          <template #default="{ row }"
            ><CodeTag category="production.taskStatus" :code="row.status"
          /></template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="router.push(`/production/tasks/${row.taskBusinessId}`)"
            >
              详情
            </el-button>
            <el-button
              link
              type="primary"
              @click="router.push(`/production/verify?task=${row.taskBusinessId}`)"
            >
              核验
            </el-button>
            <el-button
              v-if="row.status === 'ARRANGED' || row.status === 'PARTIALLY_VERIFIED'"
              link
              type="danger"
              @click="cancelTask(row)"
            >
              取消
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </PageState>

    <el-dialog v-model="dialogVisible" title="安排任务" width="520px">
      <el-form label-width="112px">
        <el-form-item label="任务类型">
          <el-select v-model="form.taskType">
            <el-option label="其他任务（无生产来源）" value="OTHER_TASK" />
            <el-option label="正常生产" value="NORMAL_PRODUCTION" />
          </el-select>
        </el-form-item>
        <el-form-item label="执行员工">
          <el-select v-model="form.employeeBusinessId" filterable>
            <el-option
              v-for="employee in employees.filter((item) => item.employmentStatus === 'EMPLOYED')"
              :key="employee.employeeBusinessId"
              :label="`${employee.name}（${employee.employeeNo}）`"
              :value="employee.employeeBusinessId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="工作日期">
          <el-input :model-value="filters.workDate" disabled />
        </el-form-item>
        <template v-if="form.taskType === 'OTHER_TASK'">
          <el-form-item label="工作内容" required>
            <el-input v-model="form.workContent" type="textarea" :rows="2" />
          </el-form-item>
          <el-form-item label="整体标准分钟">
            <el-input v-model.number="form.enteredStandardMinutes" type="number" />
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="生产来源" required>
            <el-input v-model="form.sourceId" placeholder="生产来源标识" />
          </el-form-item>
          <el-form-item label="执行工序">
            <el-select v-model="form.processCode">
              <el-option label="制作" value="MAKING" />
              <el-option label="捏毛装袋" value="BAGGING" />
              <el-option label="缝边剪袋" value="SEAM_CUTTING" />
            </el-select>
          </el-form-item>
          <el-form-item label="计划数量">
            <el-input v-model.number="form.plannedQuantity" type="number" />
          </el-form-item>
        </template>
        <el-form-item label="超量原因">
          <el-input v-model="form.overrideReason" placeholder="仅超出标准负载时填写" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="arrange">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.schedule-page {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 16px;
}

.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}

.alert {
  margin-bottom: 12px;
}
</style>
