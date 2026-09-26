import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { App } from 'antd';
import { ProductionWorkbenchPage } from './ProductionWorkbenchPage';
import { todayString } from './productionLogic';
import type { OvertimeTaskView, ProductionTaskItemView, ProductionTaskView } from '../../api/production';

function item(overrides: Partial<ProductionTaskItemView> = {}): ProductionTaskItemView {
  return {
    id: 101,
    itemNo: 1,
    orderId: 1,
    orderNo: 'YM00001',
    orderItemId: 11,
    lineNo: 1,
    productId: 21,
    productNo: 'P0001',
    productName: '草莓熊',
    node: 'MAKING',
    plannedQuantity: 10,
    sourceType: 'ORDER',
    sourceId: 11,
    standardMinutes: 6,
    estimatedMinutes: 60,
    actualInflow: 0,
    verifiedProcessedQuantity: 0,
    executableQuantity: 0,
    waitingUpstream: false,
    status: 'PENDING',
    version: 0,
    ...overrides,
  };
}

function task(overrides: Partial<ProductionTaskView> = {}): ProductionTaskView {
  return {
    id: 1,
    taskNo: 'PT000001',
    taskDate: '2026-09-23',
    employeeId: 7,
    employeeName: '张三',
    workTypeId: 2,
    workTypeName: '制作',
    taskType: 'NORMAL',
    note: null,
    derivedStatus: 'SCHEDULED',
    items: [item()],
    version: 0,
    ...overrides,
  };
}

function renderPage(tasks: ProductionTaskView[], overtimeTasks: OvertimeTaskView[] = []): string {
  return renderToStaticMarkup(
    <App>
      <MemoryRouter initialEntries={['/production']}>
        <Routes>
          <Route
            path="/production"
            element={
              <ProductionWorkbenchPage
                initialTasks={tasks}
                initialSchedules={[]}
                initialReminders={[]}
                initialOvertimeTasks={overtimeTasks}
              />
            }
          />
        </Routes>
      </MemoryRouter>
    </App>,
  );
}

const overtimeTask: OvertimeTaskView = {
  id: 9,
  taskNo: 'OT000009',
  taskDate: '2026-09-23',
  employeeId: 7,
  employeeName: '张三',
  workType: 'MAKING',
  workTypeName: '制作',
  note: null,
  version: 0,
  items: [],
  preemptions: [
    {
      id: 31,
      overtimeItemId: 41,
      futureTaskItemId: 101,
      orderId: 1,
      orderItemId: 11,
      node: 'MAKING',
      preemptedQuantity: 4,
      status: 'ACTIVE',
    },
  ],
  reminders: [
    {
      id: 51,
      reminderType: 'PLAN_ADJUSTMENT',
      orderId: 1,
      orderItemId: 11,
      node: 'MAKING',
      preemptionId: 31,
      futureTaskItemId: 101,
      quantity: 4,
      status: 'OPEN',
    },
  ],
};

describe('生产工作台（任务 5.17）', () => {
  it('rendersHorizontalWeekCalendar', () => {
    const html = renderPage([task()]);
    // 周一→周日 7 个日期列，日期是主横向维度
    for (const weekday of ['周一', '周二', '周三', '周四', '周五', '周六', '周日']) {
      expect(html).toContain(weekday);
    }
    expect(html.match(/data-testid="week-day-\d{4}-\d{2}-\d{2}"/g)).toHaveLength(7);
    // 2026-09-23 是周三，任务卡落在周三列
    expect(html).toContain('data-testid="week-day-2026-09-23"');
    expect(html).toContain('PT000001');
  });

  it('filtersEmployeeWithoutMakingEmployeeNavigation', () => {
    const html = renderPage([
      task({ id: 1, taskNo: 'PT000001', employeeId: 7, employeeName: '张三', items: [item({ id: 101 })] }),
      task({ id: 2, taskNo: 'PT000002', employeeId: 8, employeeName: '李四', items: [item({ id: 102 })] }),
    ]);
    // 员工是属性筛选（下拉），不是主导航分组
    expect(html).toContain('员工（属性筛选）');
    expect(html).toContain('data-testid="production-filters"');
    // 切换员工不改变日期列，也不隐藏跨员工同日任务：同一天列同时出现两位员工
    const wednesday = html.slice(html.indexOf('week-day-2026-09-23'));
    expect(wednesday).toContain('张三');
    expect(wednesday).toContain('李四');
  });

  it('showsOnlyOneCreateEntry', () => {
    const html = renderPage([]);
    // 唯一「新建排班」按钮（OtherSchedule 的提示文案不含 >新建排班<）
    expect(html.match(/>新建排班</g)).toHaveLength(1);
    // 没有分散的四个主创建按钮，也没有来源/额度顶级导航
    for (const forbidden of ['新建计划', '新建超额任务', '新建其他排班', 'production-sources', 'rework-sources-nav']) {
      expect(html).not.toContain(forbidden);
    }
  });

  it('showsOvertimePreemptionRemindersInsideWorkbench', () => {
    const html = renderPage([task()], [overtimeTask]);
    // 超额提醒留在工作台/提醒区，不新增顶级导航
    expect(html).toContain('data-testid="overtime-reminder-area"');
    expect(html).toContain('data-testid="overtime-task-9"');
    expect(html).toContain('OT000009');
    expect(html).toContain('预占 4（有效）');
    expect(html).toContain('计划调整 4（待处理）');
    // 仍然只有一个「新建排班」入口，且没有超额任务导航
    expect(html.match(/>新建排班</g)).toHaveLength(1);
    expect(html).not.toContain('overtime-tasks-nav');
  });

  it('rendersBothViewsWithEmployeeGroupedTodayList', () => {
    const today = todayString();
    const html = renderPage([
      task({
        id: 1,
        taskNo: 'PT000001',
        employeeId: 7,
        employeeName: '张三',
        taskDate: today,
        items: [item({ id: 101, waitingUpstream: true })],
      }),
      task({
        id: 2,
        taskNo: 'PT000002',
        employeeId: 8,
        employeeName: '李四',
        taskDate: today,
        derivedStatus: 'VERIFIED',
        items: [item({ id: 102, status: 'VERIFIED' })],
      }),
    ]);
    // 视图切换：今日列表 + 周历两个视图都渲染（非激活视图仅隐藏）
    expect(html).toContain('data-testid="production-view-tabs"');
    expect(html).toContain('今日列表（按员工分组）');
    expect(html).toContain('周历');
    expect(html).toContain('data-testid="production-view-today"');
    expect(html).toContain('data-testid="production-view-week"');
    // 今日汇总条：4 格 + 蓝/绿/橙变体
    expect(html).toContain('class="today-summary"');
    expect(html).toContain('summary-blue');
    expect(html).toContain('summary-green');
    expect(html).toContain('summary-orange');
    // 按员工分组：员工色点（Badge）只在分组标题出现一次，产品行不重复
    expect(html).toContain('class="employee-heading"');
    const zhangGroup = html.slice(html.indexOf('today-group-7'), html.indexOf('today-group-8'));
    expect(zhangGroup).toContain('ant-badge-status-dot');
    expect(zhangGroup.match(/张三/g)).toHaveLength(1);
    expect(zhangGroup).toContain('today-row');
    const zhangRow = zhangGroup.slice(zhangGroup.indexOf('today-row-1'));
    expect(zhangRow).not.toContain('ant-badge-status-dot');
    expect(zhangRow).not.toContain('张三');
    // 已完成行弱化
    expect(html.slice(html.indexOf('today-group-8'))).toContain('is-done');
    // 周历：week-head + week-grid + 7 个 day-cell + 每列蓝色「+ 安排」（对齐原型）
    expect(html).toContain('class="week-head"');
    expect(html).toContain('class="week-grid"');
    expect(html.match(/class="day-cell/g)).toHaveLength(7);
    expect(html.match(/data-testid="add-day-\d{4}-\d{2}-\d{2}"/g)).toHaveLength(7);
    expect(html).toContain('+ 安排');
    // 周卡：员工色左边框类 + 异常态 issue + 完成态 done
    expect(html).toMatch(/week-card employee-\w+ issue/);
    expect(html).toMatch(/week-card employee-\w+ done/);
  });

  it('matchesPrototypeLayoutSpec', () => {
    const today = todayString();
    const html = renderPage([
      task({ id: 1, taskDate: today, items: [item({ id: 101 })] }),
    ]);
    // 今日汇总条 4 列、员工行 5 列、周历 7 列（含 min-width 1050）
    expect(html).toContain('grid-template-columns:repeat(4, 1fr)');
    expect(html).toContain('grid-template-columns:minmax(230px, 1.8fr) 110px 130px 145px 150px');
    expect(html).toContain('grid-template-columns:repeat(7, minmax(150px, 1fr))');
    expect(html).toContain('min-width:1050px');
  });
});
