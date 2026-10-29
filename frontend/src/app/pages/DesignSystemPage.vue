<script setup lang="ts">
import { ref } from 'vue'
import { Bell } from '@element-plus/icons-vue'
import type {
  DetailFact,
  SectionTab,
  SelectOption,
  SourceNode,
  TableColumn,
  TaskStatus,
  TimelineItem,
  TopNavItem,
} from '@/shared/ui/types'
import UiActionBar from '@/shared/ui/UiActionBar.vue'
import UiButton from '@/shared/ui/UiButton.vue'
import UiDetailFacts from '@/shared/ui/UiDetailFacts.vue'
import UiDialogForm from '@/shared/ui/UiDialogForm.vue'
import UiCapacityBar from '@/shared/ui/UiCapacityBar.vue'
import UiDataTable from '@/shared/ui/UiDataTable.vue'
import UiDateColumn from '@/shared/ui/UiDateColumn.vue'
import UiEmployeeBadge from '@/shared/ui/UiEmployeeBadge.vue'
import UiEmptyState from '@/shared/ui/UiEmptyState.vue'
import UiFilterChip from '@/shared/ui/UiFilterChip.vue'
import UiFormField from '@/shared/ui/UiFormField.vue'
import UiIconButton from '@/shared/ui/UiIconButton.vue'
import UiPageBar from '@/shared/ui/UiPageBar.vue'
import UiSectionTabs from '@/shared/ui/UiSectionTabs.vue'
import UiSourceChain from '@/shared/ui/UiSourceChain.vue'
import UiTimeline from '@/shared/ui/UiTimeline.vue'
import UiPanel from '@/shared/ui/UiPanel.vue'
import UiProgressRing from '@/shared/ui/UiProgressRing.vue'
import UiSearchField from '@/shared/ui/UiSearchField.vue'
import UiSelectField from '@/shared/ui/UiSelectField.vue'
import UiStatusPill from '@/shared/ui/UiStatusPill.vue'
import UiTaskCard from '@/shared/ui/UiTaskCard.vue'
import UiTextField from '@/shared/ui/UiTextField.vue'
import UiFormSection from '@/shared/ui/UiFormSection.vue'
import UiTopNav from '@/shared/ui/UiTopNav.vue'

const navItems: TopNavItem[] = [
  { key: 'home', label: '总览' },
  { key: 'orders', label: '订单' },
  { key: 'production', label: '生产' },
  { key: 'finance', label: '财务' },
  { key: 'workforce', label: '人员' },
  { key: 'reporting', label: '报表' },
]

const search = ref('')
const process = ref('MAKING')
const taskNo = ref('')
const quantity = ref('')
const activeFilter = ref('all')
const detailTab = ref('overview')
const dialogOpen = ref(false)

const processOptions: SelectOption[] = [
  { label: '制作', value: 'MAKING' },
  { label: '捏毛装袋', value: 'BAGGING' },
  { label: '缝边剪袋', value: 'SEAM_CUTTING' },
]

const sectionTabs: SectionTab[] = [
  { key: 'overview', label: '概览' },
  { key: 'items', label: '明细', count: 4 },
  { key: 'history', label: '记录', count: 3 },
]

const detailFacts: DetailFact[] = [
  { label: '执行工序', value: '制作' },
  { label: '计划数量', value: '40 件' },
  { label: '标准工时', value: '4 小时' },
  { label: '工作日期', value: '10月28日', hint: '周三' },
  { label: '执行主体', value: '王秀兰', hint: '制作' },
  { label: '生产来源', value: '订单项 · 待交付' },
]

const timelineItems: TimelineItem[] = [
  {
    title: '任务已安排',
    description: '王秀兰 · 制作 · 40 件 · 标准 4 小时',
    time: '09:12',
    tone: 'done',
  },
  { title: '开始制作', description: '任务进入执行，未记录开工时间', time: '09:30', tone: 'done' },
  {
    title: '等待最终核验',
    description: '录入合格、返工、报废与未完成数量',
    time: '现在',
    tone: 'current',
  },
]

const sourceNodes: SourceNode[] = [
  { label: '订单', value: 'O-20261026-04', hint: '云朵与星星系列' },
  { label: '订单项', value: '星星玩偶 · 中号', hint: '订购 40 件' },
  { label: '生产来源', value: 'PSS-20261026-04', hint: '可交付 40 件' },
  { label: '商品快照', value: 'PSNAP-20261020-11', hint: '手工玩偶 v3' },
]

interface BoardTask {
  taskNo: string
  product: string
  variant: string
  employeeName: string
  colorIndex: number
  typeLabel: string
  quantity: number
  status: TaskStatus
}

interface BoardColumn {
  weekday: string
  date: string
  today: boolean
  meta: string
  capacityValue: number
  capacityMax: number
  tasks: BoardTask[]
}

const boardColumns: BoardColumn[] = [
  {
    weekday: '周一',
    date: '26',
    today: false,
    meta: '3 项 · 160 件',
    capacityValue: 11,
    capacityMax: 40,
    tasks: [
      {
        taskNo: 'T-1042',
        product: '云朵抱枕',
        variant: '奶白',
        employeeName: '王秀兰',
        colorIndex: 0,
        typeLabel: '制作',
        quantity: 60,
        status: 'done',
      },
      {
        taskNo: 'T-1043',
        product: '云朵抱枕',
        variant: '奶白',
        employeeName: '李桂芳',
        colorIndex: 1,
        typeLabel: '包装',
        quantity: 60,
        status: 'done',
      },
    ],
  },
  {
    weekday: '周三 · 今天',
    date: '28',
    today: true,
    meta: '3 项 · 126 件',
    capacityValue: 14,
    capacityMax: 40,
    tasks: [
      {
        taskNo: 'T-1056',
        product: '星星玩偶',
        variant: '焦糖',
        employeeName: '王秀兰',
        colorIndex: 0,
        typeLabel: '制作',
        quantity: 40,
        status: 'pending',
      },
      {
        taskNo: 'T-1059',
        product: '星星玩偶',
        variant: '焦糖',
        employeeName: '陈小雨',
        colorIndex: 2,
        typeLabel: '缝边',
        quantity: 40,
        status: 'pending',
      },
      {
        taskNo: 'T-1061',
        product: '小兔挂件',
        variant: '小组',
        employeeName: '缝边一组',
        colorIndex: 4,
        typeLabel: '缝边',
        quantity: 90,
        status: 'pending',
      },
    ],
  },
  {
    weekday: '周五',
    date: '30',
    today: false,
    meta: '2 项 · 85 件',
    capacityValue: 7,
    capacityMax: 40,
    tasks: [
      {
        taskNo: 'T-1068',
        product: '小兔挂件',
        variant: '灰',
        employeeName: '王秀兰',
        colorIndex: 0,
        typeLabel: '超额',
        quantity: 40,
        status: 'arranged',
      },
      {
        taskNo: 'T-1069',
        product: '星星玩偶',
        variant: '焦糖',
        employeeName: '赵春梅',
        colorIndex: 3,
        typeLabel: '制作',
        quantity: 45,
        status: 'arranged',
      },
    ],
  },
]

const tableColumns: TableColumn[] = [
  { key: 'taskNo', label: '任务编号', width: 120 },
  { key: 'product', label: '商品', minWidth: 160 },
  { key: 'employee', label: '执行人', width: 120 },
  { key: 'process', label: '工序', width: 110 },
  { key: 'quantity', label: '数量', width: 90, align: 'right' },
  { key: 'status', label: '状态', width: 110, align: 'right' },
]

const tableRows: Record<string, unknown>[] = [
  {
    taskNo: 'T-1056',
    product: '星星玩偶',
    employee: '王秀兰',
    process: '制作',
    quantity: 40,
    status: 'pending',
  },
  {
    taskNo: 'T-1057',
    product: '云朵抱枕',
    employee: '王秀兰',
    process: '返工',
    quantity: 6,
    status: 'pending',
  },
  {
    taskNo: 'T-1058',
    product: '星星玩偶',
    employee: '李桂芳',
    process: '包装',
    quantity: 40,
    status: 'pending',
  },
  {
    taskNo: 'T-1062',
    product: '云朵抱枕',
    employee: '王秀兰',
    process: '制作',
    quantity: 50,
    status: 'arranged',
  },
]

function statusOf(value: unknown): TaskStatus {
  return value === 'pending' || value === 'done' ? value : 'arranged'
}

function noop(): void {
  // 组件总览页只展示形态，不触发业务动作
}
</script>

<template>
  <div class="ds-page">
    <UiTopNav :items="navItems" active="production" account-initial="陈" />

    <main class="ds-main">
      <UiPageBar
        :breadcrumb="['设计系统', '组件总览']"
        range="1600 × 900 基线"
        meta="思源黑体 · 15px 基准"
      >
        <template #actions>
          <UiButton>查看规范</UiButton>
          <UiButton variant="primary">确认基线</UiButton>
        </template>
      </UiPageBar>

      <section class="ds-section">
        <div class="ds-section__head">
          <h2 class="ds-section__title">字体层级</h2>
          <span class="ds-section__hint">Noto Sans SC / 思源黑体，400 / 500 / 600 / 700</span>
        </div>
        <div class="ds-type-grid">
          <div>
            <div class="ds-type-label">日期 · 26px / 600</div>
            <p class="ds-date">26</p>
          </div>
          <div>
            <div class="ds-type-label">任务标题 · 14px / 600</div>
            <p class="ds-card-title">云朵抱枕</p>
          </div>
          <div>
            <div class="ds-type-label">正文 · 15px / 400</div>
            <p class="ds-body">把日期、员工和任务放在同一屏里完成排班。</p>
          </div>
          <div>
            <div class="ds-type-label">辅助 · 11px / 500</div>
            <p class="ds-label">王秀兰 · 制作 · 40 件</p>
          </div>
        </div>
      </section>

      <section class="ds-section">
        <div class="ds-section__head">
          <h2 class="ds-section__title">颜色与身份</h2>
          <span class="ds-section__hint">墨色负责层级，橙色只做强调，员工色只做识别</span>
        </div>
        <div class="ds-swatches">
          <div>
            <span class="ds-swatch" :style="{ background: 'var(--yumi-ds-ink)' }"></span>
            <span class="ds-swatch-label">Ink</span>
          </div>
          <div>
            <span class="ds-swatch" :style="{ background: 'var(--yumi-ds-accent)' }"></span>
            <span class="ds-swatch-label">Accent</span>
          </div>
          <div>
            <span class="ds-swatch" :style="{ background: 'var(--yumi-ds-emp-green)' }"></span>
            <span class="ds-swatch-label">员工 1</span>
          </div>
          <div>
            <span class="ds-swatch" :style="{ background: 'var(--yumi-ds-emp-amber)' }"></span>
            <span class="ds-swatch-label">员工 2</span>
          </div>
          <div>
            <span class="ds-swatch" :style="{ background: 'var(--yumi-ds-emp-blue)' }"></span>
            <span class="ds-swatch-label">员工 3</span>
          </div>
          <div>
            <span class="ds-swatch" :style="{ background: 'var(--yumi-ds-emp-rose)' }"></span>
            <span class="ds-swatch-label">员工 4</span>
          </div>
          <div>
            <span class="ds-swatch" :style="{ background: 'var(--yumi-ds-emp-violet)' }"></span>
            <span class="ds-swatch-label">员工 5</span>
          </div>
          <div>
            <span class="ds-swatch" :style="{ background: 'var(--yumi-ds-line)' }"></span>
            <span class="ds-swatch-label">Line</span>
          </div>
        </div>
      </section>

      <section class="ds-section">
        <div class="ds-section__head">
          <h2 class="ds-section__title">按钮</h2>
          <span class="ds-section__hint">全页只有一个墨黑主操作；筛选当前态不再抢层级</span>
        </div>
        <div class="ds-row">
          <UiButton variant="primary">安排任务</UiButton>
          <UiButton>导出</UiButton>
          <UiButton variant="ghost">取消</UiButton>
          <UiButton variant="danger">删除</UiButton>
          <UiButton size="sm">小按钮</UiButton>
          <UiButton disabled>不可用</UiButton>
          <UiIconButton label="通知"
            ><el-icon :size="17"><Bell /></el-icon
          ></UiIconButton>
        </div>
      </section>

      <section class="ds-section">
        <div class="ds-section__head">
          <h2 class="ds-section__title">表单与筛选</h2>
          <span class="ds-section__hint">统一控件高度、标签字号和错误呈现</span>
        </div>
        <UiPanel>
          <div class="ds-form-grid">
            <UiFormField label="搜索" hint="支持任务编号、商品或员工">
              <UiSearchField v-model="search" placeholder="搜索任务、商品或员工" />
            </UiFormField>
            <UiFormField label="执行工序" required>
              <UiSelectField v-model="process" :options="processOptions" />
            </UiFormField>
            <UiFormField label="任务编号">
              <UiTextField v-model="taskNo" placeholder="T-1056" />
            </UiFormField>
            <UiFormField label="计划数量" error="计划数量必须大于 0">
              <UiTextField v-model="quantity" placeholder="0" />
            </UiFormField>
          </div>
          <div class="ds-row ds-row--divided">
            <UiFilterChip
              label="全部"
              :count="18"
              :active="activeFilter === 'all'"
              @click="activeFilter = 'all'"
            />
            <UiFilterChip
              label="待核验"
              :count="4"
              :active="activeFilter === 'pending'"
              @click="activeFilter = 'pending'"
            />
            <UiFilterChip
              label="超额"
              :count="2"
              :active="activeFilter === 'extra'"
              @click="activeFilter = 'extra'"
            />
            <UiFilterChip label="待排" :count="2" tone="todo" />
            <UiProgressRing :percent="81" label="6.5h" caption="今日已排" />
          </div>
        </UiPanel>
      </section>

      <section class="ds-section">
        <div class="ds-section__head">
          <h2 class="ds-section__title">状态与员工</h2>
          <span class="ds-section__hint">状态是描边胶囊，员工色只出现在头像、姓名和卡片左缘</span>
        </div>
        <UiPanel>
          <div class="ds-row">
            <UiStatusPill status="pending" />
            <UiStatusPill status="done" />
            <UiStatusPill status="arranged" />
            <UiStatusPill status="extra" label="制作超额" />
            <UiStatusPill status="repair" label="返工" />
          </div>
          <div class="ds-row ds-row--divided">
            <UiEmployeeBadge name="王秀兰" :color-index="0" />
            <UiEmployeeBadge name="李桂芳" :color-index="1" />
            <UiEmployeeBadge name="陈小雨" :color-index="2" />
            <UiEmployeeBadge name="赵春梅" :color-index="3" />
            <UiEmployeeBadge name="缝边一组" :color-index="4" />
            <UiCapacityBar :value="14" :max="40" label="已排 14.0 / 40h" />
          </div>
        </UiPanel>
      </section>

      <section class="ds-section">
        <div class="ds-section__head">
          <h2 class="ds-section__title">详情、页签与操作</h2>
          <span class="ds-section__hint">详情只读，操作显式进入；底部操作栏固定</span>
        </div>
        <UiPanel>
          <UiSectionTabs v-model="detailTab" :items="sectionTabs" />
          <div class="ds-detail-grid">
            <UiDetailFacts :items="detailFacts" />
            <UiTimeline :items="timelineItems" />
          </div>
          <div class="ds-row ds-row--divided">
            <UiSourceChain :nodes="sourceNodes" />
          </div>
          <UiActionBar :sticky="false">
            <span>调整日期不会改变已核验事实</span>
            <template #actions>
              <UiButton>取消</UiButton>
              <UiButton variant="primary" @click="dialogOpen = true">保存</UiButton>
            </template>
          </UiActionBar>
        </UiPanel>

        <UiDialogForm
          v-model="dialogOpen"
          title="调整任务"
          confirm-text="保存"
          @confirm="dialogOpen = false"
        >
          <UiFormSection title="任务信息" hint="调整后重新校验负载与来源">
            <div class="ds-form-grid">
              <UiFormField label="工作日期" required>
                <UiTextField v-model="taskNo" placeholder="2026-10-28" />
              </UiFormField>
              <UiFormField label="计划数量">
                <UiTextField v-model="quantity" placeholder="40" />
              </UiFormField>
            </div>
          </UiFormSection>
        </UiDialogForm>
      </section>

      <section class="ds-section">
        <div class="ds-section__head">
          <h2 class="ds-section__title">日期列与任务卡</h2>
          <span class="ds-section__hint">日期列承担排班；卡片只保留员工、商品、数量、状态</span>
        </div>
        <div class="ds-board">
          <UiDateColumn
            v-for="column in boardColumns"
            :key="column.date"
            :weekday="column.weekday"
            :date="column.date"
            :today="column.today"
            :meta="column.meta"
            :capacity-value="column.capacityValue"
            :capacity-max="column.capacityMax"
            @add="noop"
          >
            <UiTaskCard
              v-for="task in column.tasks"
              :key="task.taskNo"
              :task-no="task.taskNo"
              :product="task.product"
              :variant="task.variant"
              :employee-name="task.employeeName"
              :color-index="task.colorIndex"
              :type-label="task.typeLabel"
              :quantity="task.quantity"
              :status="task.status"
              @click="noop"
            />
          </UiDateColumn>
        </div>
      </section>

      <section class="ds-section">
        <div class="ds-section__head">
          <h2 class="ds-section__title">数据表</h2>
          <span class="ds-section__hint">金额与数量右对齐，表头粘性，空态统一</span>
        </div>
        <UiDataTable
          :columns="tableColumns"
          :rows="tableRows"
          row-key="taskNo"
          compact
          empty-text="没有匹配的任务"
        >
          <template #status="{ row }">
            <UiStatusPill :status="statusOf(row.status)" />
          </template>
        </UiDataTable>
      </section>

      <section class="ds-section">
        <div class="ds-section__head">
          <h2 class="ds-section__title">空态</h2>
          <span class="ds-section__hint">安静的添加入口，不用大块空白</span>
        </div>
        <UiPanel>
          <UiEmptyState title="当前没有可安排任务" hint="调整筛选条件，或从订单创建生产需求" />
        </UiPanel>
      </section>
    </main>
  </div>
</template>

<style scoped>
.ds-page {
  min-height: 100vh;
  background: var(--yumi-ds-canvas);
  color: var(--yumi-ds-text);
  font-family: var(--yumi-ds-font-ui);
  font-size: var(--yumi-ds-font-size-body);
}

.ds-main {
  width: 100%;
  max-width: var(--yumi-ds-content-width);
  margin: 0 auto;
  padding: var(--yumi-ds-space-2xl) var(--yumi-ds-space-4xl) var(--yumi-ds-space-5xl);
  display: flex;
  flex-direction: column;
  gap: var(--yumi-ds-space-4xl);
}

.ds-section {
  display: flex;
  flex-direction: column;
  gap: var(--yumi-ds-space-xl);
}

.ds-section__head {
  display: flex;
  align-items: baseline;
  gap: var(--yumi-ds-space-lg);
  padding-bottom: var(--yumi-ds-space-md);
  border-bottom: 1px solid var(--yumi-ds-line);
}

.ds-section__title {
  margin: 0;
  color: var(--yumi-ds-ink);
  font-size: var(--yumi-ds-font-size-card-title);
  font-weight: var(--yumi-ds-font-weight-semibold);
}

.ds-section__hint {
  color: var(--yumi-ds-muted);
  font-size: var(--yumi-ds-font-size-label);
}

.ds-type-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--yumi-ds-space-2xl);
}

.ds-type-label {
  color: var(--yumi-ds-faint);
  font-size: var(--yumi-ds-font-size-meta);
  letter-spacing: 0.1em;
}

.ds-date,
.ds-card-title,
.ds-body,
.ds-label {
  margin: var(--yumi-ds-space-sm) 0 0;
  color: var(--yumi-ds-ink);
}

.ds-date {
  font-family: var(--yumi-ds-font-numeric);
  font-size: var(--yumi-ds-font-size-date);
  font-weight: var(--yumi-ds-font-weight-semibold);
  line-height: 1.1;
  letter-spacing: -0.03em;
}

.ds-card-title {
  font-size: var(--yumi-ds-font-size-card-title);
  font-weight: var(--yumi-ds-font-weight-semibold);
}

.ds-body {
  max-width: 28em;
  color: var(--yumi-ds-text);
  font-size: var(--yumi-ds-font-size-body);
  line-height: var(--yumi-ds-line-height-ui);
}

.ds-label {
  color: var(--yumi-ds-muted);
  font-size: var(--yumi-ds-font-size-label);
  font-weight: var(--yumi-ds-font-weight-medium);
}

.ds-swatches {
  display: flex;
  flex-wrap: wrap;
  gap: var(--yumi-ds-space-xl);
}

.ds-swatch {
  display: block;
  width: var(--yumi-ds-swatch-width);
  height: var(--yumi-ds-swatch-height);
  border: 1px solid var(--yumi-ds-line);
  border-radius: var(--yumi-ds-radius-control);
}

.ds-swatch-label {
  display: block;
  margin-top: var(--yumi-ds-space-xs);
  color: var(--yumi-ds-muted);
  font-size: var(--yumi-ds-font-size-meta);
}

.ds-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--yumi-ds-space-md);
}

.ds-row--divided {
  margin-top: var(--yumi-ds-space-2xl);
  padding-top: var(--yumi-ds-space-2xl);
  border-top: 1px solid var(--yumi-ds-line-soft);
}

.ds-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--yumi-ds-space-2xl);
}

.ds-detail-grid {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
  gap: var(--yumi-ds-space-3xl);
  margin-top: var(--yumi-ds-space-2xl);
}

.ds-board {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--yumi-ds-week-gap);
}

.ds-board .yui-daycol {
  min-height: var(--yumi-ds-board-preview-height);
}

@media (max-width: 1200px) {
  .ds-type-grid,
  .ds-swatches {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .ds-form-grid,
  .ds-board {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
