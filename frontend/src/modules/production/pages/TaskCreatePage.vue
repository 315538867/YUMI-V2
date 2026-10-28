<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import type { EmployeeView, TeamView } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import type {
  AvailableProductionSourceView,
  TargetProcess,
  TaskType,
} from '@/modules/production/contracts'
import { productionService } from '@/modules/production/services/productionService'
import { productService } from '@/modules/product/services/productService'
import CodeLabel from '@/shared/components/CodeLabel.vue'
import CodeTag from '@/shared/components/CodeTag.vue'
import PageState from '@/shared/components/PageState.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'
import PageShell from '@/shared/components/PageShell.vue'

type CreateTaskType = Extract<
  TaskType,
  'OTHER_TASK' | 'NORMAL_PRODUCTION' | 'EXTRA_PRODUCTION' | 'REPAIR'
>

interface SelectedSource {
  source: AvailableProductionSourceView
  quantity: number
}

const router = useRouter()
const taskType = ref<CreateTaskType>('OTHER_TASK')
const subjectType = ref<'EMPLOYEE' | 'TEAM'>('EMPLOYEE')
const subjectBusinessId = ref('')
const workDate = ref(new Date().toISOString().slice(0, 10))
const processCode = ref<TargetProcess>('MAKING')
const selected = ref<SelectedSource[]>([])
const sources = ref<AvailableProductionSourceView[]>([])
const productNames = ref<Record<string, string>>({})
const employees = ref<EmployeeView[]>([])
const teams = ref<TeamView[]>([])
const { loading, errorText, run } = useAsyncLoad(true)
const { loading: sourceLoading, errorText: sourceError, run: runSources } = useAsyncLoad()
const { loading: saving, run: runAction } = useAsyncAction()

const other = reactive({
  workContent: '',
  enteredStandardMinutes: 60,
  relatedProductBusinessId: '',
  plannedQuantity: null as number | null,
})

const isOtherTask = computed(() => taskType.value === 'OTHER_TASK')
const visibleSources = computed(() =>
  taskType.value === 'REPAIR'
    ? sources.value.filter((source) => source.demandType === 'RETURNED_PRODUCT_REPAIR')
    : sources.value.filter((source) => source.demandType !== 'RETURNED_PRODUCT_REPAIR'),
)
const subjectOptions = computed(() =>
  subjectType.value === 'EMPLOYEE'
    ? employees.value.filter((employee) => employee.employmentStatus === 'EMPLOYED')
    : teams.value.filter((team) => team.status === 'ACTIVE'),
)

async function load(): Promise<void> {
  await run(async () => {
    ;[employees.value, teams.value] = await Promise.all([
      workforceService.employees(),
      workforceService.teams(),
    ])
    subjectBusinessId.value = employees.value[0]?.employeeBusinessId ?? ''
    if (!isOtherTask.value) {
      await loadSources()
    }
  }, '新建任务基础数据加载失败')
}

async function loadSources(): Promise<void> {
  selected.value = []
  if (isOtherTask.value) {
    sources.value = []
    return
  }
  await runSources(async () => {
    sources.value = await productionService.availableSources(processCode.value)
    const names: Record<string, string> = {}
    await Promise.all(
      [...new Set(sources.value.map((source) => source.productBusinessId))].map(async (id) => {
        try {
          names[id] = (await productService.get(id)).name
        } catch {
          names[id] = id
        }
      }),
    )
    productNames.value = names
  }, '可安排来源加载失败')
}

function changeTaskType(): void {
  subjectBusinessId.value =
    subjectType.value === 'EMPLOYEE'
      ? ((subjectOptions.value[0] as EmployeeView | undefined)?.employeeBusinessId ?? '')
      : ((subjectOptions.value[0] as TeamView | undefined)?.teamBusinessId ?? '')
  if (taskType.value === 'EXTRA_PRODUCTION') {
    processCode.value = 'MAKING'
  }
  if (isOtherTask.value) {
    sources.value = []
    selected.value = []
  } else {
    void loadSources()
  }
}

function changeSubjectType(): void {
  subjectBusinessId.value = ''
}

function addSource(source: AvailableProductionSourceView): void {
  if (
    selected.value.some(
      (row) => row.source.productionSourceBusinessId === source.productionSourceBusinessId,
    )
  ) {
    return
  }
  selected.value.push({ source, quantity: 1 })
}

function removeSource(index: number): void {
  selected.value.splice(index, 1)
}

function sourceLabel(source: AvailableProductionSourceView): string {
  return `${productNames.value[source.productBusinessId] ?? source.productBusinessId} / ${source.schemeBusinessId}`
}

function validate(): string | null {
  if (subjectBusinessId.value === '') {
    return subjectType.value === 'EMPLOYEE' ? '请选择执行员工' : '请选择执行小组'
  }
  if (workDate.value === '') {
    return '请选择工作日期'
  }
  if (isOtherTask.value) {
    if (other.workContent.trim() === '') {
      return '请填写工作内容'
    }
    if (!other.enteredStandardMinutes || other.enteredStandardMinutes <= 0) {
      return '请填写整体标准分钟'
    }
    if (
      other.relatedProductBusinessId.trim() !== '' &&
      (!other.plannedQuantity || other.plannedQuantity <= 0)
    ) {
      return '关联商品时必须填写计划数量'
    }
    return null
  }
  if (selected.value.length === 0) {
    return '请至少选择一个可安排来源'
  }
  if (taskType.value === 'REPAIR' && selected.value.length !== 1) {
    return '返工任务只允许关联一个返工来源'
  }
  for (const row of selected.value) {
    if (!row.quantity || row.quantity <= 0) {
      return '每条来源的计划数量必须大于 0'
    }
    if (row.quantity > row.source.availableQuantity) {
      return `来源可安排数量不足：可用 ${row.source.availableQuantity}`
    }
  }
  return null
}

async function save(): Promise<void> {
  const invalid = validate()
  if (invalid !== null) {
    errorText.value = invalid
    return
  }
  await runAction(
    () =>
      productionService.arrange({
        taskType: taskType.value,
        subject: {
          subjectType: subjectType.value,
          subjectBusinessId: subjectBusinessId.value,
        },
        workDate: workDate.value,
        processCode: isOtherTask.value ? null : processCode.value,
        items: isOtherTask.value
          ? []
          : selected.value.map((row) => ({
              productionSourceBusinessId: row.source.productionSourceBusinessId,
              plannedQuantity: Number(row.quantity),
            })),
        enteredStandardMinutes: isOtherTask.value ? Number(other.enteredStandardMinutes) : null,
        otherTask: isOtherTask.value
          ? {
              workContent: other.workContent.trim(),
              relatedProductBusinessId:
                other.relatedProductBusinessId.trim() === ''
                  ? null
                  : other.relatedProductBusinessId.trim(),
              plannedQuantity:
                other.relatedProductBusinessId.trim() === '' ? null : Number(other.plannedQuantity),
            }
          : null,
        overrideReason: null,
      }),
    {
      success: '任务已安排',
      fallback: '任务安排失败',
      onSuccess: (created) => {
        void router.push(`/production/tasks/${created.taskBusinessId}`)
      },
    },
  )
}

onMounted(load)
</script>

<template>
  <PageShell title="新建任务" hint="正常生产与超额生产从可安排来源进入；其他任务不关联来源。">
    <template #actions>
      <el-button @click="router.push('/production/schedule')">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存并查看任务</el-button>
    </template>
    <WorkspacePage class="task-create-page">
      <PageState :loading="loading" :error="errorText" @retry="load">
        <el-form label-width="120px" class="form">
          <el-form-item label="任务类型">
            <el-select v-model="taskType" class="medium" @change="changeTaskType">
              <el-option label="其他任务" value="OTHER_TASK" />
              <el-option label="正常生产" value="NORMAL_PRODUCTION" />
              <el-option label="制作超额" value="EXTRA_PRODUCTION" />
              <el-option label="返工" value="REPAIR" />
            </el-select>
          </el-form-item>
          <el-form-item label="执行主体类型">
            <el-radio-group v-model="subjectType" @change="changeSubjectType">
              <el-radio value="EMPLOYEE">员工</el-radio>
              <el-radio value="TEAM">小组</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="执行主体">
            <el-select v-model="subjectBusinessId" filterable class="medium">
              <el-option
                v-for="subject in subjectOptions"
                :key="
                  'employeeBusinessId' in subject
                    ? subject.employeeBusinessId
                    : subject.teamBusinessId
                "
                :label="
                  'employeeBusinessId' in subject
                    ? `${subject.name}（${subject.employeeNo}）`
                    : `${subject.name}（${subject.teamNo}）`
                "
                :value="
                  'employeeBusinessId' in subject
                    ? subject.employeeBusinessId
                    : subject.teamBusinessId
                "
              />
            </el-select>
          </el-form-item>
          <el-form-item label="工作日期">
            <el-date-picker v-model="workDate" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>

          <template v-if="isOtherTask">
            <el-form-item label="工作内容">
              <el-input v-model="other.workContent" type="textarea" :rows="2" />
            </el-form-item>
            <el-form-item label="整体标准分钟">
              <el-input v-model.number="other.enteredStandardMinutes" type="number" class="short" />
            </el-form-item>
            <el-form-item label="关联商品">
              <el-input v-model="other.relatedProductBusinessId" placeholder="可选，商品业务标识" />
            </el-form-item>
            <el-form-item v-if="other.relatedProductBusinessId.trim() !== ''" label="计划数量">
              <el-input v-model.number="other.plannedQuantity" type="number" class="short" />
            </el-form-item>
          </template>

          <el-form-item v-else label="执行工序">
            <el-select
              v-model="processCode"
              class="medium"
              :disabled="taskType === 'EXTRA_PRODUCTION'"
              @change="loadSources"
            >
              <el-option label="制作" value="MAKING" />
              <el-option label="捏毛装袋" value="BAGGING" />
              <el-option label="缝边剪袋" value="SEAM_CUTTING" />
            </el-select>
          </el-form-item>
        </el-form>

        <template v-if="!isOtherTask">
          <el-alert
            v-if="sourceError"
            type="error"
            :closable="false"
            :title="sourceError"
            class="yumi-alert"
          />
          <div class="yumi-card-grid yumi-card-grid--2">
            <section class="source-panel">
              <h2>可安排来源</h2>
              <el-table
                v-loading="sourceLoading"
                :data="visibleSources"
                empty-text="当前工序没有可安排来源"
                border
                size="small"
              >
                <el-table-column label="商品 / 方案" min-width="200">
                  <template #default="{ row }">{{ sourceLabel(row) }}</template>
                </el-table-column>
                <el-table-column label="需求类型" width="140">
                  <template #default="{ row }">
                    <CodeLabel
                      category="production.demandType"
                      :code="row.demandType"
                      fallback="其他需求"
                    />
                  </template>
                </el-table-column>
                <el-table-column label="需求工序" width="120">
                  <template #default="{ row }">
                    <CodeTag category="production.targetProcess" :code="row.targetProcess" />
                  </template>
                </el-table-column>
                <el-table-column prop="availableQuantity" label="可安排" width="90" />
                <el-table-column label="操作" width="90">
                  <template #default="{ row }">
                    <el-button link type="primary" @click="addSource(row)">添加</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </section>

            <section class="source-panel">
              <h2>已选来源（{{ selected.length }}）</h2>
              <el-table :data="selected" empty-text="从左侧添加来源" border size="small">
                <el-table-column label="商品 / 方案" min-width="200">
                  <template #default="{ row }">{{ sourceLabel(row.source) }}</template>
                </el-table-column>
                <el-table-column label="本次数量" width="140">
                  <template #default="{ row }">
                    <el-input v-model.number="row.quantity" type="number" />
                  </template>
                </el-table-column>
                <el-table-column label="可用" width="80">
                  <template #default="{ row }">{{ row.source.availableQuantity }}</template>
                </el-table-column>
                <el-table-column label="操作" width="80">
                  <template #default="{ index }">
                    <el-button link type="danger" @click="removeSource(index)">移除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </section>
          </div>
        </template>
      </PageState>
    </WorkspacePage>
  </PageShell>
</template>

<style scoped>
.form {
  max-width: 760px;
}

.short {
  width: 140px;
}

.medium {
  width: 260px;
}

.source-panel {
  min-width: 0;
}
</style>
