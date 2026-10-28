<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useListQuery } from '@/platform/composables/useListQuery'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import type { EmployeeView, TeamView } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import type { TaskView } from '@/modules/production/contracts'
import { productionService } from '@/modules/production/services/productionService'
import CodeTag from '@/shared/components/CodeTag.vue'
import PageState from '@/shared/components/PageState.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'

const { loading, errorText, run } = useAsyncLoad()
const { run: runAction } = useAsyncAction()

const router = useRouter()
const tasks = ref<TaskView[]>([])
const employees = ref<EmployeeView[]>([])
const teams = ref<TeamView[]>([])

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

const today = isoDate(new Date())
const { filters, search, reset } = useListQuery(
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

const weekLabel = computed(() => `${weekDates.value[0]} ~ ${weekDates.value[6]}`)
const isWeekView = computed(() => filters.view === 'week')

const filteredTasks = computed(() => {
  const keyword = filters.keyword.trim().toLowerCase()
  if (keyword === '') {
    return tasks.value
  }
  return tasks.value.filter(
    (task) =>
      task.taskNo.toLowerCase().includes(keyword) ||
      task.items.some((item) => item.productBusinessId.toLowerCase().includes(keyword)),
  )
})

function subjectKey(task: TaskView): string {
  return `${task.subject.subjectType}:${task.subject.subjectBusinessId}`
}

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

const subjectRows = computed(() => {
  const rows = new Map<string, string>()
  for (const task of filteredTasks.value) {
    rows.set(subjectKey(task), subjectName(task))
  }
  return [...rows.entries()]
    .map(([key, label]) => ({ key, label }))
    .sort((left, right) => left.label.localeCompare(right.label, 'zh-CN'))
})

function tasksOfDay(subject: string, date: string): TaskView[] {
  return filteredTasks.value.filter(
    (task) => subjectKey(task) === subject && task.workDate === date,
  )
}

const overview = computed(() => ({
  taskCount: filteredTasks.value.length,
  plannedQuantity: filteredTasks.value.reduce((sum, task) => sum + task.plannedWorkQuantity, 0),
  subjectCount: subjectRows.value.length,
  pendingVerification: filteredTasks.value.filter(
    (task) => task.status === 'ARRANGED' || task.status === 'PARTIALLY_VERIFIED',
  ).length,
  extraTaskCount: filteredTasks.value.filter((task) => task.taskType === 'EXTRA_PRODUCTION').length,
}))

function dayLabel(date: string): string {
  const weekday = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][parseDate(date).getDay()]
  return `${date.slice(5)} ${weekday}`
}

async function load(): Promise<void> {
  await run(async () => {
    const range = isWeekView.value
      ? productionService.tasksBetween(weekDates.value[0], weekDates.value[6])
      : productionService.tasksBetween()
    ;[tasks.value, employees.value, teams.value] = await Promise.all([
      range,
      workforceService.employees(),
      workforceService.teams(),
    ])
  }, '排班加载失败')
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

async function cancelTask(task: TaskView): Promise<void> {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt('请输入取消原因', `取消任务 ${task.taskNo}`, {
      inputValidator: (value) => (value.trim() === '' ? '取消原因不能为空' : true),
    })
    reason = result.value
  } catch {
    return
  }
  await runAction(() => productionService.cancelTask(task.taskBusinessId, reason), {
    success: '任务已取消；未核验计划占用已释放',
    fallback: '任务取消失败',
    onSuccess: load,
  })
}

void load()
</script>

<template>
  <WorkspacePage class="schedule-page">
    <div class="yumi-toolbar">
      <el-radio-group v-model="filters.view" @change="search">
        <el-radio-button value="week">周视图</el-radio-button>
        <el-radio-button value="list">全部列表</el-radio-button>
      </el-radio-group>
      <template v-if="isWeekView">
        <el-button @click="shiftWeek(-7)">上周</el-button>
        <el-button @click="goToday">今天</el-button>
        <el-button @click="shiftWeek(7)">下周</el-button>
        <span class="week-label">{{ weekLabel }}</span>
      </template>
      <el-input
        v-model="filters.keyword"
        placeholder="搜索任务或商品"
        clearable
        class="keyword"
        aria-label="搜索任务或商品"
        @keyup.enter="search"
      />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <PermissionButton
        permission="production.schedule"
        @click="router.push('/production/tasks/new')"
        >安排任务</PermissionButton
      >
    </div>

    <div class="yumi-stat-grid yumi-stat-grid--5">
      <div class="yumi-stat-card">
        <span class="yumi-stat-card__label">{{ isWeekView ? '本周任务' : '全部任务' }}</span>
        <span class="yumi-stat-card__value">{{ overview.taskCount }}<small>个</small></span>
      </div>
      <div class="yumi-stat-card">
        <span class="yumi-stat-card__label">计划数量</span>
        <span class="yumi-stat-card__value">{{ overview.plannedQuantity }}<small>件</small></span>
      </div>
      <div class="yumi-stat-card">
        <span class="yumi-stat-card__label">执行主体</span>
        <span class="yumi-stat-card__value">{{ overview.subjectCount }}<small>个</small></span>
      </div>
      <div class="yumi-stat-card">
        <span class="yumi-stat-card__label">待核验</span>
        <span class="yumi-stat-card__value"
          >{{ overview.pendingVerification }}<small>个任务</small></span
        >
      </div>
      <div class="yumi-stat-card">
        <span class="yumi-stat-card__label">制作超额</span>
        <span class="yumi-stat-card__value"
          >{{ overview.extraTaskCount }}<small>个任务</small></span
        >
      </div>
    </div>

    <PageState :loading="loading" :error="errorText" @retry="load">
      <template v-if="isWeekView">
        <el-empty v-if="subjectRows.length === 0" description="本周没有匹配的任务" />
        <div v-else class="week-board">
          <div class="week-row week-head">
            <div class="subject-cell">执行主体</div>
            <div v-for="date in weekDates" :key="date" class="day-cell">{{ dayLabel(date) }}</div>
          </div>
          <div v-for="row in subjectRows" :key="row.key" class="week-row">
            <div class="subject-cell">{{ row.label }}</div>
            <div v-for="date in weekDates" :key="`${row.key}-${date}`" class="day-cell">
              <button
                v-for="task in tasksOfDay(row.key, date)"
                :key="task.taskBusinessId"
                type="button"
                class="task-card"
                @click="router.push(`/production/tasks/${task.taskBusinessId}`)"
              >
                <strong>{{ task.taskNo }}</strong>
                <span class="task-meta">
                  <CodeTag category="production.taskType" :code="task.taskType" />
                  <CodeTag
                    v-if="task.processCode"
                    category="production.targetProcess"
                    :code="task.processCode"
                  />
                </span>
                <span class="task-meta">
                  <span>{{ task.plannedWorkQuantity }} 件</span>
                  <CodeTag category="production.taskStatus" :code="task.status" />
                </span>
              </button>
            </div>
          </div>
        </div>
      </template>

      <el-table v-else :data="filteredTasks" empty-text="没有匹配的任务" border>
        <el-table-column prop="workDate" label="计划日期" width="120" />
        <el-table-column prop="taskNo" label="任务编号" width="140" />
        <el-table-column label="类型" width="150">
          <template #default="{ row }">
            <CodeTag category="production.taskType" :code="row.taskType" />
          </template>
        </el-table-column>
        <el-table-column label="执行主体" min-width="150">
          <template #default="{ row }">{{ subjectName(row) }}</template>
        </el-table-column>
        <el-table-column label="工序" width="120">
          <template #default="{ row }">
            <CodeTag
              v-if="row.processCode"
              category="production.targetProcess"
              :code="row.processCode"
            />
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="plannedWorkQuantity" label="计划量" width="90" />
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <CodeTag category="production.taskStatus" :code="row.status" />
          </template>
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
  </WorkspacePage>
</template>

<style scoped>
.week-label {
  color: var(--yumi-text-secondary);
  font-size: var(--yumi-font-size-base);
}

.keyword {
  width: 220px;
}

.week-board {
  border: 1px solid var(--yumi-border-color);
  border-radius: var(--yumi-radius-md);
  overflow-x: auto;
}

.week-row {
  display: grid;
  grid-template-columns: 140px repeat(7, minmax(150px, 1fr));
  border-bottom: 1px solid var(--yumi-border-color-light);
}

.week-row:last-child {
  border-bottom: none;
}

.week-head {
  background: var(--yumi-bg-muted);
  font-weight: var(--yumi-font-weight-semibold);
}

.subject-cell,
.day-cell {
  padding: var(--yumi-space-2);
  border-right: 1px solid var(--yumi-border-color-light);
  min-height: 56px;
}

.day-cell:last-child {
  border-right: none;
}

.subject-cell {
  display: flex;
  align-items: center;
}

.day-cell {
  display: flex;
  flex-direction: column;
  gap: var(--yumi-space-2);
}

.task-card {
  display: flex;
  flex-direction: column;
  gap: var(--yumi-space-1);
  width: 100%;
  padding: var(--yumi-space-2);
  text-align: left;
  background: var(--yumi-surface);
  border: 1px solid var(--yumi-border-color);
  border-radius: var(--yumi-radius-md);
  cursor: pointer;
}

.task-card:hover {
  border-color: var(--yumi-color-brand);
}

.task-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--yumi-space-1);
  color: var(--yumi-text-secondary);
  font-size: var(--yumi-font-size-sm);
}
</style>
