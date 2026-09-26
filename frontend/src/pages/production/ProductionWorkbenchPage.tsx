import { useEffect, useMemo, useState } from 'react';
import { App, Badge, Button, Card, Segmented, Space, Tag, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import dayjs from 'dayjs';
import {
  listIncompleteReminders,
  listOtherSchedules,
  listOvertimeTasks,
  listProductionTasks,
  TASK_TYPE_LABELS,
  type IncompleteReminderView,
  type OtherScheduleView,
  type OvertimeTaskView,
  type ProductionTaskView,
} from '../../api/production';
import { listEmployees, listProducts, type EmployeeView } from '../../api/catalog';
import { listOrders } from '../../api/orders';
import { listStaticDataItems } from '../../api/staticData';
import { describeApiError } from '../../api/errors';
import { NODE_LABELS } from '../../api/inventory';
import { ProductionFilters, type FilterOption, type ProductionFilterValue } from './ProductionFilters';
import { ProductionWeekCalendar } from './ProductionWeekCalendar';
import { OtherScheduleSection } from './OtherScheduleSection';
import {
  employeeBadgeColor,
  employeeColorClass,
  groupTasksByEmployee,
  isTaskDone,
  remindersByItemNode,
  shiftWeek,
  taskHasIssue,
  todayString,
  weekDays,
  weekRange,
} from './productionLogic';

export interface ProductionWorkbenchPageProps {
  /** 测试注入：给定后不再发起初始加载，直接渲染服务端数据。 */
  initialTasks?: ProductionTaskView[];
  initialSchedules?: OtherScheduleView[];
  initialReminders?: IncompleteReminderView[];
  initialEmployees?: EmployeeView[];
  initialOvertimeTasks?: OvertimeTaskView[];
}

/** 超额预占状态与提醒类型的中文文案（不把英文枚举直接显示给用户）。 */
const PREEMPTION_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '有效',
  RELEASED: '已释放',
};

const OVERTIME_REMINDER_LABELS: Record<string, string> = {
  OVERTIME_PENDING_VERIFY: '超额待核验',
  PLAN_ADJUSTMENT: '计划调整',
};

type WorkbenchView = 'today' | 'week';

/**
 * 生产工作台（阶段五 5.17，对齐原型 index.html）：
 * 视图切换（今日列表按员工分组 / 周历）+ 员工属性筛选 + 唯一「新建排班」入口。
 * 没有来源/额度顶级导航，没有四个分散的主创建按钮；来源与余额只在明细上下文解释。
 * 刷新与切换周历只发 GET，取消/返回不写；员工色点只在员工分组标题出现一次，产品行不重复。
 */
export function ProductionWorkbenchPage({
  initialTasks,
  initialSchedules,
  initialReminders,
  initialEmployees,
  initialOvertimeTasks,
}: ProductionWorkbenchPageProps = {}) {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [anchor, setAnchor] = useState(todayString());
  const [view, setView] = useState<WorkbenchView>('week');
  const [filters, setFilters] = useState<ProductionFilterValue>({});
  const [tasks, setTasks] = useState<ProductionTaskView[]>(initialTasks ?? []);
  const [schedules, setSchedules] = useState<OtherScheduleView[]>(initialSchedules ?? []);
  const [reminders, setReminders] = useState<IncompleteReminderView[]>(initialReminders ?? []);
  const [employees, setEmployees] = useState<EmployeeView[]>(initialEmployees ?? []);
  const [overtimeTasks, setOvertimeTasks] = useState<OvertimeTaskView[]>(initialOvertimeTasks ?? []);
  const [orders, setOrders] = useState<FilterOption[]>([]);
  const [products, setProducts] = useState<FilterOption[]>([]);
  const [workTypes, setWorkTypes] = useState<FilterOption[]>([]);
  const [loading, setLoading] = useState(false);

  const [dateFrom, dateTo] = weekRange(anchor);
  const today = todayString();

  useEffect(() => {
    (async () => {
      try {
        const [employeeList, orderList, productList, workTypeList] = await Promise.all([
          listEmployees({ status: 'ACTIVE' }),
          listOrders({ status: 'CONFIRMED' }),
          listProducts({ status: 'ACTIVE' }),
          listStaticDataItems('WORK_TYPE'),
        ]);
        setEmployees(employeeList);
        setOrders(orderList.map(order => ({ value: order.id, label: `${order.orderNo} ${order.customerName}` })));
        setProducts(productList.map(product => ({ value: product.id, label: `${product.productNo} ${product.name}` })));
        setWorkTypes(
          workTypeList
            .filter(item => ['MAKING', 'PACKING_BAG', 'SEAM_CUTTING'].includes(item.code ?? ''))
            .map(item => ({ value: item.id, label: item.name })),
        );
      } catch (error) {
        message.error(describeApiError(error));
      }
    })();
  }, []);

  async function reload(currentFilters: ProductionFilterValue = filters, currentAnchor: string = anchor) {
    const [from, to] = weekRange(currentAnchor);
    setLoading(true);
    try {
      const days = weekDays(currentAnchor).map(day => day.date);
      const [taskList, scheduleList, reminderList, overtimeLists] = await Promise.all([
        listProductionTasks({
          dateFrom: from,
          dateTo: to,
          employeeId: currentFilters.employeeId,
          workTypeId: currentFilters.workTypeId,
          taskType: currentFilters.taskType,
          status: currentFilters.status,
          orderId: currentFilters.orderId,
          productId: currentFilters.productId,
        }),
        listOtherSchedules({ dateFrom: from, dateTo: to, employeeId: currentFilters.employeeId }),
        listIncompleteReminders(),
        Promise.all(days.map(date => listOvertimeTasks(date))),
      ]);
      setTasks(taskList);
      setSchedules(scheduleList);
      setReminders(reminderList);
      setOvertimeTasks(overtimeLists.flat());
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void reload(filters, anchor);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [anchor, filters]);

  const reminderMap = useMemo(() => remindersByItemNode(reminders), [reminders]);
  const employeeOptions = useMemo<FilterOption[]>(
    () => employees.map(entry => ({ value: entry.id!, label: `${entry.employeeNo} ${entry.name}` })),
    [employees],
  );

  const todayTasks = useMemo(() => tasks.filter(task => task.taskDate === today), [tasks, today]);
  const todayGroups = useMemo(() => groupTasksByEmployee(todayTasks), [todayTasks]);
  const todayDone = todayTasks.filter(isTaskDone).length;
  const todayPendingVerify = todayTasks.filter(task =>
    task.items.some(item => item.status === 'PENDING'),
  ).length;
  const todayIssues = todayTasks.filter(task => taskHasIssue(task, reminderMap)).length;

  return (
    <div className="production-page">
      <Card
        title={
          <Space>
            <Typography.Title level={4} style={{ margin: 0 }}>
              生产工作台
            </Typography.Title>
            <Tag color="blue">数量由服务端事实派生</Tag>
          </Space>
        }
        extra={
          <Space>
            <Button onClick={() => setAnchor(shiftWeek(anchor, -1))}>上一周</Button>
            <Button onClick={() => setAnchor(todayString())}>本周</Button>
            <Button onClick={() => setAnchor(shiftWeek(anchor, 1))}>下一周</Button>
            <Button onClick={() => void reload()} loading={loading}>
              刷新
            </Button>
            <Button type="primary" onClick={() => navigate('/production/tasks/new')}>
              新建排班
            </Button>
          </Space>
        }
      >
        <Space orientation="vertical" size={12} style={{ width: '100%' }}>
          <ProductionFilters
            value={filters}
            employees={employeeOptions}
            workTypes={workTypes}
            orders={orders}
            products={products}
            onChange={patch => setFilters(previous => ({ ...previous, ...patch }))}
          />

          <div className="view-tabs" data-testid="production-view-tabs">
            <Segmented<WorkbenchView>
              value={view}
              onChange={value => setView(value)}
              options={[
                { label: '今日列表（按员工分组）', value: 'today' },
                { label: '周历', value: 'week' },
              ]}
            />
            <Typography.Text type="secondary">
              周区间 {dateFrom} ~ {dateTo} · 日期是主横向维度
            </Typography.Text>
          </div>

          <div data-testid="production-view-today" style={{ display: view === 'today' ? undefined : 'none' }}>
            <div
              className="today-summary"
              data-testid="today-summary"
              style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)' }}
            >
              <div className="summary-blue">
                <span>今日任务</span>
                <strong>{todayTasks.length}</strong>
                <small>个</small>
              </div>
              <div className="summary-green">
                <span>已完成</span>
                <strong>{todayDone}</strong>
                <small>个</small>
              </div>
              <div className="summary-orange">
                <span>待核验</span>
                <strong>{todayPendingVerify}</strong>
                <small>个</small>
              </div>
              <div>
                <span>异常提醒</span>
                <strong>{todayIssues}</strong>
                <small>个</small>
              </div>
            </div>
            <div className="today-toolbar">
              <Typography.Text strong>今日列表（{today}，按员工分组）</Typography.Text>
              <Typography.Text type="secondary">员工是属性分组，不是导航</Typography.Text>
            </div>
            {todayGroups.length === 0 ? (
              <Typography.Text type="secondary">今日暂无排班</Typography.Text>
            ) : (
              todayGroups.map(group => {
                const color = employeeBadgeColor(group.employeeId);
                return (
                  <div
                    key={group.employeeId}
                    className={['employee-group', employeeColorClass(group.employeeId)].join(' ')}
                    data-testid={`today-group-${group.employeeId}`}
                  >
                    <div className="employee-heading">
                      <Space size={0} style={{ minWidth: 0 }}>
                        <Badge color={color} text={group.employeeName} />
                        <Typography.Text type="secondary" style={{ marginLeft: 8 }}>
                          {group.tasks.length} 个任务 ·{' '}
                          {group.tasks[0]?.workTypeName ?? '未指定工序'}
                        </Typography.Text>
                      </Space>
                    </div>
                    {group.tasks.map(task => {
                      const done = isTaskDone(task);
                      const issue = taskHasIssue(task, reminderMap);
                      const firstItem = task.items[0];
                      const hasPending = task.items.some(item => item.status === 'PENDING');
                      return (
                        <div
                          key={task.id}
                          className={['today-row', done ? 'is-done' : ''].filter(Boolean).join(' ')}
                          data-testid={`today-row-${task.id}`}
                          style={{
                            display: 'grid',
                            gridTemplateColumns: 'minmax(230px, 1.8fr) 110px 130px 145px 150px',
                          }}
                        >
                          <div className="today-status">
                            <i className={['status-dot', done ? 'green' : issue ? '' : 'gray'].filter(Boolean).join(' ')} />
                            <div>
                              <b>{task.taskNo}</b>
                              <span>
                                {TASK_TYPE_LABELS[task.taskType] ?? task.taskType}
                                {issue ? ' · 有提醒' : ''}
                              </span>
                            </div>
                          </div>
                          <div>
                            <b style={{ display: 'block', fontSize: 13 }}>
                              {task.workTypeName || NODE_LABELS[firstItem?.node ?? 'MAKING']}
                            </b>
                            <span style={{ fontSize: 12, color: '#999' }}>工序</span>
                          </div>
                          <div className="today-plan">
                            <b>{firstItem?.plannedQuantity ?? '—'}</b>
                            <span>计划数量</span>
                          </div>
                          <div className="today-result">
                            <b>{firstItem?.verifiedProcessedQuantity ?? '—'}</b>
                            <span>已核验处理</span>
                          </div>
                          <div className="today-actions">
                            <Space size={4}>
                              <Button size="small" type="link" onClick={() => navigate(`/production/tasks/${task.id}`)}>
                                查看
                              </Button>
                              {hasPending && (
                                <Button
                                  size="small"
                                  type="link"
                                  onClick={() => navigate(`/production/tasks/${task.id}/verify`)}
                                >
                                  核验
                                </Button>
                              )}
                            </Space>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                );
              })
            )}
          </div>

          <div data-testid="production-view-week" style={{ display: view === 'week' ? undefined : 'none' }}>
            <ProductionWeekCalendar
              anchor={anchor}
              tasks={tasks}
              remindersByItemNode={reminderMap}
              onOpenTask={task => navigate(`/production/tasks/${task.id}`)}
              onVerifyTask={task => navigate(`/production/tasks/${task.id}/verify`)}
              onAddTask={() => navigate('/production/tasks/new')}
            />
          </div>

          <Card
            size="small"
            data-testid="overtime-reminder-area"
            title={
              <Space>
                <Typography.Text strong>超额预占提醒</Typography.Text>
                <Typography.Text type="secondary">
                  执行当天超额任务的预占与计划调整提醒（按所选周日期列出，不产生商品数量）
                </Typography.Text>
              </Space>
            }
          >
            {overtimeTasks.length === 0 ? (
              <Typography.Text type="secondary">本周末超额任务</Typography.Text>
            ) : (
              <Space orientation="vertical" size={8} style={{ width: '100%' }}>
                {overtimeTasks.map(overtime => (
                  <div key={overtime.id} data-testid={`overtime-task-${overtime.id}`}>
                    <Space size={8} wrap>
                      <Typography.Text strong>{overtime.taskNo}</Typography.Text>
                      <Typography.Text type="secondary">{overtime.taskDate}</Typography.Text>
                      <Typography.Text>{overtime.employeeName}</Typography.Text>
                      {overtime.workTypeName ? <Tag>{overtime.workTypeName}</Tag> : null}
                    </Space>
                    <div style={{ marginTop: 4 }}>
                      <Space size={4} wrap>
                        {overtime.preemptions.map(preemption => (
                          <Tag key={`preempt-${preemption.id}`} color="geekblue">
                            {`预占 ${preemption.preemptedQuantity}（${PREEMPTION_STATUS_LABELS[preemption.status] ?? preemption.status}）`}
                          </Tag>
                        ))}
                        {overtime.reminders.map(reminder => (
                          <Tag
                            key={`reminder-${reminder.id}`}
                            color={reminder.status === 'OPEN' ? 'orange' : 'default'}
                          >
                            {`${OVERTIME_REMINDER_LABELS[reminder.reminderType] ?? '提醒'} ${reminder.quantity}（${reminder.status === 'OPEN' ? '待处理' : '已处理'}）`}
                          </Tag>
                        ))}
                        {overtime.preemptions.length === 0 && overtime.reminders.length === 0 ? (
                          <Typography.Text type="secondary">无预占或提醒</Typography.Text>
                        ) : null}
                      </Space>
                    </div>
                  </div>
                ))}
              </Space>
            )}
          </Card>
          <OtherScheduleSection schedules={schedules} loading={loading} onChanged={() => void reload()} />
        </Space>
      </Card>
    </div>
  );
}

/** 供测试断言「日期是主横向维度」的辅助导出。 */
export function workbenchWeekRange(anchor: string): [string, string] {
  return weekRange(dayjs(anchor).format('YYYY-MM-DD'));
}
