<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { codeLabel } from '@/platform/display/codeCatalog'
import { useListQuery } from '@/platform/composables/useListQuery'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import { useAuthStore } from '@/platform/stores/auth'
import type { EmployeeView, TeamView } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import type { TaskStatus, TaskView } from '@/modules/production/contracts'
import { productionService } from '@/modules/production/services/productionService'
import { productService } from '@/modules/product/services/productService'
import type { TableColumn } from '@/shared/ui/types'
import UiButton from '@/shared/ui/UiButton.vue'
import UiDataTable from '@/shared/ui/UiDataTable.vue'
import UiDateColumn from '@/shared/ui/UiDateColumn.vue'
import UiFilterChip from '@/shared/ui/UiFilterChip.vue'
import UiIconButton from '@/shared/ui/UiIconButton.vue'
import UiPageBar from '@/shared/ui/UiPageBar.vue'
import UiSearchField from '@/shared/ui/UiSearchField.vue'
import UiStatusPill from '@/shared/ui/UiStatusPill.vue'
import UiTaskCard from '@/shared/ui/UiTaskCard.vue'
import PageState from '@/shared/components/PageState.vue'

type FilterKey = 'all' | 'pending' | 'extra' | 'repair'

const auth = useAuthStore()
const router = useRouter()
const { loading, errorText, run } = useAsyncLoad()
const { run: runAction } = useAsyncAction()

const tasks = ref<TaskView[]>([])
const employees = ref<EmployeeView[]>([])
const teams = ref<TeamView[]>([])
const productNames = ref<Record<string, string>>({})
const activeFilter = ref<FilterKey>('all')

function isoDate(value: Date): string {
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  return `${value.getFullYear()}-${month}-${day}`
}

function parseDate(value: string): Date {
  return new Date(`${value}T00:00:00`)
}

function mondayOf(value: string): string {
  const date = parseDate(value)
  const offset = (date.getDay() + 6) % 7
  date.setDate(date.getDate() - offset)
  return isoDate(date)
}

function shortDate(value: string): string {
  return `${value.slice(5, 7)}/${value.slice(8, 10)}`
}

const today = isoDate(new Date())
const { filters, search } = useListQuery(
  { weekStart: mondayOf(today), view: 'week', keyword: '' },
  { paged: false, onSearch: () => void load() },
)

const weekDates = computed(() =>
  Array.from({ length: 7 }, (_, index) => {
    const date = parseDate(filters.weekStart)
    date.setDate(date.getDate() + index)
    return isoDate(date)
  }),
)

const weekLabel = computed(
  () => `${shortDate(weekDates.value[0] ?? today)} — ${shortDate(weekDates.value[6] ?? today)}`,
)
const isWeekView = computed(() => filters.view === 'week')

const canSchedule = computed(() => auth.hasPermission('production.schedule'))
const canVerify = computed(() => auth.hasPermission('production.verify'))

const filteredTasks = computed(() => {
  const keyword = filters.keyword.trim().toLowerCase()
  return tasks.value.filter((task) => {
    if (keyword !== '' && !task.taskNo.toLowerCase().includes(keyword)) {
      return false
    }
    if (activeFilter.value === 'pending') {
      return task.status === 'ARRANGED' || task.status === 'PARTIALLY_VERIFIED'
    }
    if (activeFilter.value === 'extra') {
      return task.taskType === 'EXTRA_PRODUCTION'
    }
    if (activeFilter.value === 'repair') {
      return task.taskType === 'REPAIR'
    }
    return true
  })
})

const totalQuantity = computed(() =>
  filteredTasks.value.reduce((sum, task) => sum + task.plannedWorkQuantity, 0),
)

const filterCounts = computed(() => ({
  all: tasks.value.length,
  pending: tasks.value.filter(
    (task) => task.status === 'ARRANGED' || task.status === 'PARTIALLY_VERIFIED',
  ).length,
  extra: tasks.value.filter((task) => task.taskType === 'EXTRA_PRODUCTION').length,
  repair: tasks.value.filter((task) => task.taskType === 'REPAIR').length,
}))

function subjectName(task: TaskView): string {
  if (task.subject.subjectType === 'EMPLOYEE') {
    return (
      employees.value.find((item) => item.employeeBusinessId === task.subject.subjectBusinessId)
        ?.name ?? task.subject.subjectBusinessId
    )
  }
  return (
    teams.value.find((item) => item.teamBusinessId === task.subject.subjectBusinessId)?.name ??
    task.subject.subjectBusinessId
  )
}

function productOf(task: TaskView): string {
  const first = task.items[0]?.productBusinessId ?? task.otherTask?.relatedProductBusinessId ?? ''
  if (first === '') {
    return task.otherTask?.workContent ?? task.taskNo
  }
  const name = productNames.value[first] ?? first
  return task.items.length > 1 ? `${name} 等 ${task.items.length} 项` : name
}

function processOf(task: TaskView): string {
  return task.processCode ? codeLabel('production.targetProcess', task.processCode) : '整体'
}

function typeOf(task: TaskView): string {
  return (
    {
      NORMAL_PRODUCTION: '正常',
      EXTRA_PRODUCTION: '超额',
      REPAIR: '返工',
      OTHER_TASK: '其他',
    }[task.taskType] ?? codeLabel('production.taskType', task.taskType)
  )
}

function quantityOf(task: TaskView): string {
  const quantity = task.otherTask?.plannedQuantity ?? task.plannedWorkQuantity
  return quantity > 0 ? String(quantity) : '—'
}

function unitOf(task: TaskView): string {
  return (task.otherTask?.plannedQuantity ?? task.plannedWorkQuantity) > 0 ? '件' : ''
}

function cardStatusOf(task: TaskView): 'pending' | 'done' | 'arranged' {
  if (task.status === 'VERIFIED') {
    return 'done'
  }
  if (task.status === 'PARTIALLY_VERIFIED') {
    return 'pending'
  }
  return 'arranged'
}

function statusLabelOf(taskStatus: unknown): string {
  const value = String(taskStatus ?? '')
  return value === 'ARRANGED' ? '已排' : codeLabel('production.taskStatus', value, '—')
}

function tasksOfDay(date: string): TaskView[] {
  return filteredTasks.value.filter((task) => task.workDate === date)
}

function quantityOfDay(date: string): number {
  return tasksOfDay(date).reduce((sum, task) => sum + task.plannedWorkQuantity, 0)
}

function planOfDay(date: string): string {
  const tasksOfDayItems = tasksOfDay(date)
  if (tasksOfDayItems.length === 0) {
    return '无任务'
  }
  const seconds = tasksOfDayItems.reduce((sum, task) => sum + task.taskStandardSeconds, 0)
  return `计划 ${(seconds / 3600).toFixed(1)} 小时`
}

function weekdayOf(date: string): string {
  return ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][parseDate(date).getDay()] ?? '周日'
}

function isToday(date: string): boolean {
  return date === today
}

function setView(view: 'week' | 'list'): void {
  filters.view = view
  void search()
}

function shiftWeek(days: number): void {
  const date = parseDate(filters.weekStart)
  date.setDate(date.getDate() + days)
  filters.weekStart = isoDate(date)
  void search()
}

function goToday(): void {
  filters.weekStart = mondayOf(today)
  void search()
}

function openTask(task: TaskView): void {
  void router.push(`/production/tasks/${task.taskBusinessId}`)
}

function openCreate(date?: string): void {
  void router.push({ path: '/production/tasks/new', query: date ? { date } : {} })
}

function openVerify(task: TaskView): void {
  void router.push(`/production/verify?task=${task.taskBusinessId}`)
}

async function cancelTask(taskBusinessId: string, taskNo: string): Promise<void> {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt('请输入取消原因', `取消任务 ${taskNo}`, {
      inputValidator: (value) => (value.trim() === '' ? '取消原因不能为空' : true),
    })
    reason = result.value
  } catch {
    return
  }
  await runAction(() => productionService.cancelTask(taskBusinessId, reason), {
    success: '任务已取消；未核验计划占用已释放',
    fallback: '任务取消失败',
    onSuccess: load,
  })
}

const tableColumns: TableColumn[] = [
  { key: 'workDate', label: '计划日期', width: 120 },
  { key: 'taskNo', label: '任务编号', width: 140 },
  { key: 'subject', label: '执行主体', minWidth: 140 },
  { key: 'process', label: '工序', width: 100 },
  { key: 'quantity', label: '计划数量', width: 100, align: 'right' },
  { key: 'status', label: '状态', width: 110, align: 'right' },
  { key: 'actions', label: '操作', width: 170, align: 'right' },
]

const tableRows = computed<Record<string, unknown>[]>(() =>
  filteredTasks.value.map((task) => ({
    taskBusinessId: task.taskBusinessId,
    workDate: task.workDate,
    taskNo: task.taskNo,
    subject: subjectName(task),
    process: processOf(task),
    quantity: task.plannedWorkQuantity,
    status: task.status,
    task,
  })),
)

async function loadProductNames(items: TaskView[]): Promise<void> {
  const ids = new Set<string>()
  for (const task of items) {
    for (const item of task.items) {
      ids.add(item.productBusinessId)
    }
    if (task.otherTask?.relatedProductBusinessId) {
      ids.add(task.otherTask.relatedProductBusinessId)
    }
  }
  const names: Record<string, string> = {}
  await Promise.all(
    [...ids].map(async (id) => {
      try {
        names[id] = (await productService.get(id)).name
      } catch {
        names[id] = id
      }
    }),
  )
  productNames.value = names
}

async function load(): Promise<void> {
  await run(async () => {
    const range = isWeekView.value
      ? productionService.tasksBetween(weekDates.value[0], weekDates.value[6])
      : productionService.tasksBetween()
    const [taskList, employeeList, teamList] = await Promise.all([
      range,
      workforceService.employees(),
      workforceService.teams(),
    ])
    tasks.value = taskList
    employees.value = employeeList
    teams.value = teamList
    await loadProductNames(taskList)
  }, '排班加载失败')
}

function rowTask(row: Record<string, unknown>): TaskView {
  return row.task as TaskView
}

function rowStatus(row: Record<string, unknown>): TaskStatus {
  return String(row.status ?? '') as TaskStatus
}

void load()
</script>

<template>
  <div class="schedule-page">
    <UiPageBar
      :breadcrumb="['排班']"
      :range="weekLabel"
      :meta="`${filteredTasks.length} 个任务 · ${totalQuantity} 件`"
    >
      <template #actions>
        <UiButton v-if="canSchedule" variant="primary" @click="openCreate()">安排任务</UiButton>
      </template>
    </UiPageBar>

    <div class="yui-toolbar">
      <div class="yui-seg">
        <button
          type="button"
          class="yui-seg__item"
          :class="{ 'yui-seg__item--active': isWeekView }"
          @click="setView('week')"
        >
          周
        </button>
        <button
          type="button"
          class="yui-seg__item"
          :class="{ 'yui-seg__item--active': !isWeekView }"
          @click="setView('list')"
        >
          全部列表
        </button>
      </div>

      <template v-if="isWeekView">
        <div class="yui-weeknav">
          <UiIconButton label="上一周" @click="shiftWeek(-7)">‹</UiIconButton>
          <UiIconButton label="下一周" @click="shiftWeek(7)">›</UiIconButton>
        </div>
        <span class="yui-weeknav__label">{{ weekLabel }}</span>
        <UiButton size="sm" variant="ghost" @click="goToday">本周</UiButton>
      </template>

      <UiFilterChip
        label="全部"
        :count="filterCounts.all"
        :active="activeFilter === 'all'"
        @click="activeFilter = 'all'"
      />
      <UiFilterChip
        label="待核验"
        :count="filterCounts.pending"
        :active="activeFilter === 'pending'"
        @click="activeFilter = 'pending'"
      />
      <UiFilterChip
        label="超额"
        :count="filterCounts.extra"
        :active="activeFilter === 'extra'"
        @click="activeFilter = 'extra'"
      />
      <UiFilterChip
        label="返工"
        :count="filterCounts.repair"
        :active="activeFilter === 'repair'"
        @click="activeFilter = 'repair'"
      />

      <div class="yui-toolbar__right">
        <UiSearchField v-model="filters.keyword" placeholder="搜索任务编号" class="schedule-search" />
      </div>
    </div>

    <PageState class="schedule-body" :loading="loading" :error="errorText" @retry="load">
      <div v-if="isWeekView" class="yui-weekboard">
        <UiDateColumn
          v-for="date in weekDates"
          :key="date"
          :weekday="isToday(date) ? `${weekdayOf(date)} · 今天` : weekdayOf(date)"
          :date="date.slice(8, 10)"
          :today="isToday(date)"
          :meta="`${tasksOfDay(date).length} 项 · ${quantityOfDay(date)} 件`"
          :capacity-label="planOfDay(date)"
          :show-drop="false"
          @add="openCreate(date)"
        >
          <UiTaskCard
            v-for="task in tasksOfDay(date)"
            :key="task.taskBusinessId"
            :task-no="task.taskNo"
            :product="productOf(task)"
            :variant="processOf(task)"
            :employee-name="subjectName(task)"
            :employee-id="task.subject.subjectBusinessId"
            :type-label="typeOf(task)"
            :quantity="quantityOf(task)"
            :unit="unitOf(task)"
            :status="cardStatusOf(task)"
            :status-label="statusLabelOf(task.status)"
            @click="openTask(task)"
          />
          <div v-if="tasksOfDay(date).length === 0" class="schedule-empty-day">
            {{ isToday(date) ? '今天还没有安排' : '暂无安排' }}
          </div>
        </UiDateColumn>
      </div>

      <div v-else class="schedule-list">
        <UiDataTable
          :columns="tableColumns"
          :rows="tableRows"
          row-key="taskBusinessId"
          empty-text="没有匹配的任务"
        >
          <template #status="{ row }">
            <UiStatusPill
              :status="cardStatusOf(rowTask(row))"
              :label="statusLabelOf(row.status)"
            />
          </template>
          <template #actions="{ row }">
            <div class="schedule-row-actions">
              <UiButton size="sm" variant="ghost" @click="openTask(rowTask(row))">详情</UiButton>
              <UiButton
                v-if="canVerify && rowStatus(row) !== 'CANCELLED'"
                size="sm"
                variant="ghost"
                @click="openVerify(rowTask(row))"
              >
                核验
              </UiButton>
              <UiButton
                v-if="
                  canSchedule &&
                  rowStatus(row) !== 'CANCELLED' &&
                  rowStatus(row) !== 'VERIFIED'
                "
                size="sm"
                variant="ghost"
                @click="cancelTask(String(row.taskBusinessId), String(row.taskNo))"
              >
                取消
              </UiButton>
            </div>
          </template>
        </UiDataTable>
      </div>
    </PageState>
  </div>
</template>

<style scoped>
.schedule-page {
  display: flex;
  flex-direction: column;
  gap: var(--yumi-ds-space-md);
  height: 100%;
  min-height: 0;
}

.schedule-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.schedule-body :deep(.page-state__body) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.yui-weekboard {
  flex: 1;
  min-height: 0;
}

.schedule-search {
  width: 220px;
}

.schedule-empty-day {
  display: grid;
  place-items: center;
  min-height: var(--yumi-ds-space-5xl);
  color: var(--yumi-ds-faint);
  font-size: var(--yumi-ds-font-size-meta);
}

.schedule-list {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.schedule-row-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--yumi-ds-space-2xs);
}
</style>
