import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  DatePicker,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import { useNavigate } from 'react-router-dom';
import {
  adjustOvertimePlan,
  cancelOtherSchedule,
  cancelPlan,
  correctOtherSchedule,
  createOtherSchedule,
  createOvertimeTask,
  createPlan,
  createRemakePlan,
  createRemakeSource,
  createReworkPlan,
  createReworkSource,
  deferReminder,
  listIncompleteReminders,
  listOtherSchedules,
  listOvertimeReminders,
  listPlans,
  listRemakeSources,
  listReworkSources,
  noOvertimeAdjustment,
  PLAN_STATUS_COLORS,
  PLAN_STATUS_LABELS,
  PLAN_TYPE_LABELS,
  rescheduleReminder,
  verifyOtherSchedule,
  type IncompleteReminderView,
  type OtherScheduleView,
  type OvertimeReminderView,
  type ProductionNode,
  type ProductionPlanView,
  type RemakeSourceView,
  type ReworkSourceView,
} from '../../api/production';
import { getOrder, listOrders, type OrderSummary } from '../../api/orders';
import { listEmployees, type EmployeeView } from '../../api/catalog';
import { NODE_LABELS } from '../../api/inventory';
import { describeApiError, YumiApiError } from '../../api/errors';

const { RangePicker } = DatePicker;

const NODE_OPTIONS = (Object.keys(NODE_LABELS) as ProductionNode[]).map(value => ({
  value,
  label: NODE_LABELS[value],
}));

const SCHEDULE_STATUS_LABELS: Record<OtherScheduleView['status'], string> = {
  PENDING: '待执行',
  VERIFIED: '已核验',
  CANCELLED: '已取消',
};

/** 所有写操作收敛到一个弹窗，按 kind 决定标题、字段与提交动作。 */
type ModalState =
  | { kind: 'createPlan' }
  | { kind: 'cancelPlan'; plan: ProductionPlanView }
  | { kind: 'reschedule'; reminder: IncompleteReminderView }
  | { kind: 'defer'; reminder: IncompleteReminderView }
  | { kind: 'reworkSource' }
  | { kind: 'remakeSource' }
  | { kind: 'sourcePlan'; sourceKind: 'REWORK' | 'REMAKE'; sourceId: number; label: string }
  | { kind: 'overtime' }
  | { kind: 'adjust'; reminder: OvertimeReminderView }
  | { kind: 'noAdjust'; reminder: OvertimeReminderView }
  | { kind: 'schedule' }
  | { kind: 'scheduleVerify'; schedule: OtherScheduleView }
  | { kind: 'scheduleCancel'; schedule: OtherScheduleView }
  | { kind: 'scheduleCorrect'; schedule: OtherScheduleView }
  | null;

const MODAL_TITLES: Record<Exclude<ModalState, null>['kind'], string> = {
  createPlan: '新建生产计划',
  cancelPlan: '取消生产计划',
  reschedule: '重新安排未完成数量',
  defer: '暂不安排',
  reworkSource: '新建返工来源',
  remakeSource: '新建重做来源',
  sourcePlan: '从来源创建计划',
  overtime: '新建超额任务',
  adjust: '调整未来计划',
  noAdjust: '标记无需调整',
  schedule: '新建其他排班',
  scheduleVerify: '其他排班工时核验',
  scheduleCancel: '取消其他排班',
  scheduleCorrect: '工时更正',
};

/**
 * 生产工作台（任务 5.14）：计划 / 等待上游 / 未完成待处理 / 返工重做来源 / 其他排班。
 * 待安排、当前可执行、等待上游一律由服务端从事实派生，页面不自行计算数量；
 * 新建与处理都从显式按钮进入弹窗（页面不预置表单）；超额提醒附着在受影响的未来计划行上。
 */
export function ProductionPage() {
  const { message } = App.useApp();
  const [fieldErrors, setFieldErrors] = useState<{ field: string; message: string }[]>([]);
  const navigate = useNavigate();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [employees, setEmployees] = useState<EmployeeView[]>([]);
  const [orders, setOrders] = useState<OrderSummary[]>([]);
  const [orderItems, setOrderItems] = useState<{ value: number; label: string }[]>([]);

  const [range, setRange] = useState<[string, string] | null>(null);
  const [employeeFilter, setEmployeeFilter] = useState<number | undefined>();
  const [nodeFilter, setNodeFilter] = useState<ProductionNode | undefined>();
  const [statusFilter, setStatusFilter] = useState<string | undefined>();

  const [plans, setPlans] = useState<ProductionPlanView[]>([]);
  const [incomplete, setIncomplete] = useState<IncompleteReminderView[]>([]);
  const [overtime, setOvertime] = useState<OvertimeReminderView[]>([]);
  const [reworkSources, setReworkSources] = useState<ReworkSourceView[]>([]);
  const [remakeSources, setRemakeSources] = useState<RemakeSourceView[]>([]);
  const [schedules, setSchedules] = useState<OtherScheduleView[]>([]);
  const [sourceItemId, setSourceItemId] = useState<number | undefined>();
  const [modal, setModal] = useState<ModalState>(null);
  const [submitting, setSubmitting] = useState(false);

  async function reload() {
    setLoading(true);
    try {
      const [planList, incompleteList, overtimeList, scheduleList] = await Promise.all([
        listPlans({
          dateFrom: range?.[0],
          dateTo: range?.[1],
          employeeId: employeeFilter,
          node: nodeFilter,
          status: statusFilter,
        }),
        listIncompleteReminders(),
        listOvertimeReminders(),
        listOtherSchedules({ dateFrom: range?.[0], dateTo: range?.[1], employeeId: employeeFilter }),
      ]);
      setPlans(planList);
      setIncomplete(incompleteList);
      setOvertime(overtimeList);
      setSchedules(scheduleList);
      if (sourceItemId) {
        const [rework, remake] = await Promise.all([
          listReworkSources(sourceItemId),
          listRemakeSources(sourceItemId),
        ]);
        setReworkSources(rework);
        setRemakeSources(remake);
      } else {
        setReworkSources([]);
        setRemakeSources([]);
      }
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    (async () => {
      try {
        const [employeeList, orderList] = await Promise.all([
          listEmployees({ status: 'ACTIVE' }),
          listOrders({ status: 'CONFIRMED' }),
        ]);
        setEmployees(employeeList);
        setOrders(orderList);
      } catch (error) {
        message.error(describeApiError(error));
      }
    })();
  }, []);

  useEffect(() => {
    void reload();
  }, [range, employeeFilter, nodeFilter, statusFilter, sourceItemId]);

  /** 超额「计划待调整」提醒按受影响的未来计划归组，附着在该计划行上。 */
  const remindersByFuturePlan = useMemo(() => {
    const map = new Map<number, OvertimeReminderView>();
    for (const reminder of overtime) {
      if (reminder.reminderType === 'PLAN_ADJUSTMENT' && reminder.futurePlanId) {
        map.set(reminder.futurePlanId, reminder);
      }
    }
    return map;
  }, [overtime]);

  const waitingPlans = useMemo(() => plans.filter(plan => plan.waitingUpstream), [plans]);
  const employeeOptions = employees.map(entry => ({ value: entry.id!, label: `${entry.employeeNo} ${entry.name}` }));
  const itemOptions = useMemo(
    () =>
      plans
        .filter(plan => plan.status !== 'CANCELLED')
        .map(plan => ({
          value: plan.orderItemId,
          label: `#${plan.lineNo} ${plan.orderNo ?? ''} ${plan.productName ?? ''}`,
        })),
    [plans],
  );

  function open(next: Exclude<ModalState, null>, initial: Record<string, unknown>) {
    form.resetFields();
    form.setFieldsValue(initial);
    setFieldErrors([]);
    setModal(next);
  }

  async function pickOrder(orderId: number) {
    try {
      const order = await getOrder(orderId);
      setOrderItems(
        order.items.map(item => ({
          value: item.id,
          label: `#${item.lineNo} ${item.productNo} ${item.productName}（订购 ${item.quantity}／缝边 ${item.seamQuantity}）`,
        })),
      );
      form.setFieldValue('orderItemId', undefined);
    } catch (error) {
      message.error(describeApiError(error));
    }
  }

  /** 按弹窗类型提交到对应命令端点。 */
  async function submitModal() {
    if (!modal) {
      return;
    }
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      switch (modal.kind) {
        case 'createPlan':
          await createPlan({
            orderItemId: values.orderItemId,
            node: values.node,
            planDate: values.planDate.format('YYYY-MM-DD'),
            employeeId: values.employeeId,
            quantity: values.quantity,
            note: values.note,
          });
          break;
        case 'cancelPlan':
          await cancelPlan(modal.plan.id, values.reason);
          break;
        case 'reschedule':
          await rescheduleReminder(modal.reminder.id, {
            planDate: values.planDate.format('YYYY-MM-DD'),
            employeeId: values.employeeId,
            quantity: values.quantity,
            note: values.note,
          });
          break;
        case 'defer':
          await deferReminder(modal.reminder.id, values.reason);
          break;
        case 'reworkSource':
          await createReworkSource(values);
          break;
        case 'remakeSource':
          await createRemakeSource(values);
          break;
        case 'sourcePlan': {
          const body = {
            planDate: values.planDate.format('YYYY-MM-DD'),
            employeeId: values.employeeId,
            quantity: values.quantity,
            note: values.note,
          };
          await (modal.sourceKind === 'REWORK'
            ? createReworkPlan(modal.sourceId, body)
            : createRemakePlan(modal.sourceId, body));
          break;
        }
        case 'overtime':
          await createOvertimeTask({
            orderItemId: values.orderItemId,
            node: values.node,
            planDate: values.planDate.format('YYYY-MM-DD'),
            employeeId: values.employeeId,
            lines: [{ futurePlanId: values.futurePlanId, quantity: values.quantity }],
            note: values.note,
          });
          break;
        case 'adjust':
          await adjustOvertimePlan(modal.reminder.id, {
            newQuantity: values.newQuantity,
            reason: values.reason,
          });
          break;
        case 'noAdjust':
          await noOvertimeAdjustment(modal.reminder.id, values.reason);
          break;
        case 'schedule':
          await createOtherSchedule({
            scheduleDate: values.scheduleDate.format('YYYY-MM-DD'),
            employeeId: values.employeeId,
            hours: values.hours,
            minutes: values.minutes,
            note: values.note,
          });
          break;
        case 'scheduleVerify':
          await verifyOtherSchedule(modal.schedule.id, {
            hours: values.hours,
            minutes: values.minutes,
            note: values.note,
          });
          break;
        case 'scheduleCancel':
          await cancelOtherSchedule(modal.schedule.id, values.reason);
          break;
        case 'scheduleCorrect':
          await correctOtherSchedule(modal.schedule.id, {
            hours: values.hours,
            minutes: values.minutes,
            reason: values.reason,
          });
          break;
      }
      message.success('操作已完成');
      setModal(null);
      await reload();
    } catch (error) {
      if (error instanceof YumiApiError) {
        setFieldErrors(error.fieldErrors);
      }
      message.error(describeApiError(error));
    } finally {
      setSubmitting(false);
    }
  }

  const planColumns = [
    { title: '计划编号', dataIndex: 'planNo', width: 110 },
    {
      title: '类型',
      dataIndex: 'planType',
      width: 80,
      render: (value: keyof typeof PLAN_TYPE_LABELS) => PLAN_TYPE_LABELS[value] ?? value,
    },
    {
      title: '订单明细',
      key: 'item',
      render: (_: unknown, row: ProductionPlanView) => `#${row.lineNo} ${row.orderNo ?? ''} ${row.productName ?? ''}`,
    },
    { title: '工序', dataIndex: 'node', width: 100, render: (value: ProductionNode) => NODE_LABELS[value] },
    { title: '日期', dataIndex: 'planDate', width: 110 },
    { title: '员工', dataIndex: 'employeeName', width: 110 },
    { title: '计划', dataIndex: 'quantity', width: 70, align: 'right' as const },
    { title: '待安排', dataIndex: 'schedulableQuantity', width: 80, align: 'right' as const },
    { title: '当前可执行', dataIndex: 'executableQuantity', width: 100, align: 'right' as const },
    {
      title: '状态',
      key: 'status',
      width: 160,
      render: (_: unknown, row: ProductionPlanView) => (
        <Space size={4}>
          <Tag color={PLAN_STATUS_COLORS[row.status]}>{PLAN_STATUS_LABELS[row.status]}</Tag>
          {row.status === 'PENDING' && (row.waitingUpstream ? <Tag>等待上游</Tag> : <Tag color="cyan">可执行</Tag>)}
        </Space>
      ),
    },
    {
      title: '超额提醒',
      key: 'reminder',
      width: 200,
      render: (_: unknown, row: ProductionPlanView) => {
        const reminder = remindersByFuturePlan.get(row.id);
        if (!reminder) {
          return '—';
        }
        return (
          <Space size={4} orientation="vertical">
            <Tag color="orange">计划待调整 · 建议减少 {reminder.quantity}</Tag>
            <Space size={4}>
              <Button
                size="small"
                onClick={() =>
                  open(
                    { kind: 'adjust', reminder },
                    {
                      newQuantity: Math.max(1, row.quantity - reminder.quantity),
                      reason: undefined,
                    },
                  )
                }
              >
                调整计划
              </Button>
              <Button size="small" onClick={() => open({ kind: 'noAdjust', reminder }, { reason: undefined })}>
                无需调整
              </Button>
            </Space>
          </Space>
        );
      },
    },
    {
      title: '操作',
      key: 'action',
      width: 130,
      render: (_: unknown, row: ProductionPlanView) =>
        row.status === 'PENDING' ? (
          <Space size={4}>
            <Button type="link" size="small" onClick={() => navigate(`/production/plans/${row.id}/verify`)}>
              核验
            </Button>
            <Button
              type="link"
              size="small"
              danger
              onClick={() => open({ kind: 'cancelPlan', plan: row }, { reason: undefined })}
            >
              取消
            </Button>
          </Space>
        ) : (
          '—'
        ),
    },
  ];

  return (
    <Card
      title={
        <Space>
          <Typography.Text strong>生产工作台</Typography.Text>
          <Tag color="blue">数量由服务端事实派生</Tag>
        </Space>
      }
      extra={
        <Space>
          <Button onClick={() => open({ kind: 'createPlan' }, { planDate: dayjs() })}>新建计划</Button>
          <Button onClick={() => open({ kind: 'overtime' }, { planDate: dayjs(), quantity: 1 })}>新建超额任务</Button>
          <Button onClick={() => open({ kind: 'schedule' }, { scheduleDate: dayjs(), hours: 1, minutes: 0 })}>
            新建其他排班
          </Button>
          <Button onClick={() => void reload()} loading={loading}>
            刷新
          </Button>
        </Space>
      }
    >
      <Space size={8} style={{ marginBottom: 12 }} wrap>
        <RangePicker
          style={{ width: 240 }}
          onChange={value =>
            setRange(value?.[0] && value?.[1] ? [value[0].format('YYYY-MM-DD'), value[1].format('YYYY-MM-DD')] : null)
          }
        />
        <Select
          style={{ width: 180 }}
          allowClear
          placeholder="员工"
          value={employeeFilter}
          options={employeeOptions}
          onChange={setEmployeeFilter}
        />
        <Select style={{ width: 130 }} allowClear placeholder="工序" value={nodeFilter} options={NODE_OPTIONS} onChange={setNodeFilter} />
        <Select
          style={{ width: 130 }}
          allowClear
          placeholder="计划状态"
          value={statusFilter}
          options={Object.entries(PLAN_STATUS_LABELS).map(([value, label]) => ({ value, label }))}
          onChange={setStatusFilter}
        />
      </Space>

      <Tabs
        items={[
          {
            key: 'plans',
            label: '计划',
            children: (
              <Table<ProductionPlanView>
                size="small"
                rowKey="id"
                loading={loading}
                dataSource={plans}
                pagination={false}
                scroll={{ x: 1400 }}
                locale={{ emptyText: '暂无生产计划；从右上角「新建计划」排班' }}
                columns={planColumns}
              />
            ),
          },
          {
            key: 'waiting',
            label: '等待上游',
            children: (
              <Table<ProductionPlanView>
                size="small"
                rowKey="id"
                loading={loading}
                dataSource={waitingPlans}
                pagination={false}
                scroll={{ x: 1200 }}
                locale={{ emptyText: '没有等待上游的计划' }}
                columns={planColumns.filter(column => column.key !== 'reminder')}
              />
            ),
          },
          {
            key: 'incomplete',
            label: '未完成待处理',
            children: (
              <Table<IncompleteReminderView>
                size="small"
                rowKey="id"
                loading={loading}
                dataSource={incomplete}
                pagination={false}
                locale={{ emptyText: '没有未完成待处理' }}
                columns={[
                  { title: '原计划', dataIndex: 'planNo', width: 110 },
                  {
                    title: '订单明细',
                    key: 'item',
                    render: (_: unknown, row: IncompleteReminderView) =>
                      `#${row.lineNo} ${row.orderNo ?? ''} ${row.productName ?? ''}`,
                  },
                  { title: '工序', dataIndex: 'node', width: 100, render: (value: ProductionNode) => NODE_LABELS[value] },
                  { title: '未完成数量', dataIndex: 'quantity', width: 110, align: 'right' as const },
                  {
                    title: '已安排',
                    dataIndex: 'handledQuantity',
                    width: 90,
                    align: 'right' as const,
                    render: (value?: number | null) => value ?? '—',
                  },
                  {
                    title: '操作',
                    key: 'action',
                    width: 180,
                    render: (_: unknown, row: IncompleteReminderView) => (
                      <Space size={4}>
                        <Button
                          type="link"
                          size="small"
                          onClick={() =>
                            open(
                              { kind: 'reschedule', reminder: row },
                              { planDate: dayjs(), quantity: row.quantity, employeeId: undefined, note: undefined },
                            )
                          }
                        >
                          重新安排
                        </Button>
                        <Button type="link" size="small" onClick={() => open({ kind: 'defer', reminder: row }, { reason: undefined })}>
                          暂不安排
                        </Button>
                      </Space>
                    ),
                  },
                ]}
              />
            ),
          },
          {
            key: 'sources',
            label: '返工/重做来源',
            children: (
              <>
                <Space size={8} style={{ marginBottom: 8 }} wrap>
                  <Select
                    style={{ width: 280 }}
                    showSearch
                    optionFilterProp="label"
                    allowClear
                    placeholder="按订单明细查看来源"
                    value={sourceItemId}
                    onChange={setSourceItemId}
                    options={itemOptions}
                  />
                  <Button onClick={() => open({ kind: 'reworkSource' }, { quantity: 1 })}>新建返工来源</Button>
                  <Button onClick={() => open({ kind: 'remakeSource' }, { quantity: 1 })}>新建重做来源</Button>
                </Space>
                <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
                  来源余额 = 总量 − 已安排。核验产生的返工/报废先进入待安排额度，再按目标工序/起始工序建来源；同一核验的同一目标只有一条来源。
                </Typography.Paragraph>
                <Table<ReworkSourceView>
                  size="small"
                  rowKey="id"
                  loading={loading}
                  dataSource={reworkSources}
                  pagination={false}
                  locale={{ emptyText: '选择订单明细后显示返工来源' }}
                  columns={[
                    { title: '核验 ID', dataIndex: 'verificationId', width: 90 },
                    { title: '发现问题', dataIndex: 'foundNode', width: 110, render: (v: ProductionNode) => NODE_LABELS[v] },
                    { title: '返工目标', dataIndex: 'targetNode', width: 110, render: (v: ProductionNode) => NODE_LABELS[v] },
                    { title: '第几次', dataIndex: 'roundNo', width: 80 },
                    { title: '总量', dataIndex: 'totalQuantity', width: 70, align: 'right' as const },
                    { title: '已安排', dataIndex: 'arrangedQuantity', width: 80, align: 'right' as const },
                    { title: '余额', dataIndex: 'balanceQuantity', width: 70, align: 'right' as const },
                    {
                      title: '操作',
                      key: 'action',
                      width: 150,
                      render: (_: unknown, row: ReworkSourceView) => (
                        <Button
                          type="link"
                          size="small"
                          disabled={row.balanceQuantity <= 0}
                          onClick={() =>
                            open(
                              {
                                kind: 'sourcePlan',
                                sourceKind: 'REWORK',
                                sourceId: row.id,
                                label: `返工 ${NODE_LABELS[row.targetNode]} · 余额 ${row.balanceQuantity}`,
                              },
                              { planDate: dayjs(), quantity: row.balanceQuantity, employeeId: undefined },
                            )
                          }
                        >
                          从来源创建计划
                        </Button>
                      ),
                    },
                  ]}
                />
                <Table<RemakeSourceView>
                  size="small"
                  rowKey="id"
                  style={{ marginTop: 16 }}
                  loading={loading}
                  dataSource={remakeSources}
                  pagination={false}
                  locale={{ emptyText: '选择订单明细后显示重做来源' }}
                  columns={[
                    { title: '核验 ID', dataIndex: 'verificationId', width: 90 },
                    { title: '报废工序', dataIndex: 'scrapNode', width: 110, render: (v: ProductionNode) => NODE_LABELS[v] },
                    { title: '起始工序', dataIndex: 'startNode', width: 110, render: (v: ProductionNode) => NODE_LABELS[v] },
                    { title: '总量', dataIndex: 'totalQuantity', width: 70, align: 'right' as const },
                    { title: '已安排', dataIndex: 'arrangedQuantity', width: 80, align: 'right' as const },
                    { title: '余额', dataIndex: 'balanceQuantity', width: 70, align: 'right' as const },
                    { title: '原因', dataIndex: 'reason', render: (value?: string | null) => value ?? '—' },
                    {
                      title: '操作',
                      key: 'action',
                      width: 150,
                      render: (_: unknown, row: RemakeSourceView) => (
                        <Button
                          type="link"
                          size="small"
                          disabled={row.balanceQuantity <= 0}
                          onClick={() =>
                            open(
                              {
                                kind: 'sourcePlan',
                                sourceKind: 'REMAKE',
                                sourceId: row.id,
                                label: `重做 ${NODE_LABELS[row.startNode]} · 余额 ${row.balanceQuantity}`,
                              },
                              { planDate: dayjs(), quantity: row.balanceQuantity, employeeId: undefined },
                            )
                          }
                        >
                          从来源创建计划
                        </Button>
                      ),
                    },
                  ]}
                />
              </>
            ),
          },
          {
            key: 'schedules',
            label: '其他排班',
            children: (
              <>
                <Alert
                  type="info"
                  showIcon
                  style={{ marginBottom: 12 }}
                  title="其他排班只保存总分钟与工时事实，不产生商品、库存或订单履约数量；只能核验一次，录错用更正追加（原核验不动）。"
                />
                <Table<OtherScheduleView>
                  size="small"
                  rowKey="id"
                  loading={loading}
                  dataSource={schedules}
                  pagination={false}
                  locale={{ emptyText: '暂无其他排班' }}
                  columns={[
                    { title: '排班编号', dataIndex: 'scheduleNo', width: 110 },
                    { title: '日期', dataIndex: 'scheduleDate', width: 110 },
                    { title: '员工', dataIndex: 'employeeName', width: 110 },
                    {
                      title: '计划工时',
                      key: 'planned',
                      width: 160,
                      render: (_: unknown, row: OtherScheduleView) =>
                        `${row.hours} 时 ${row.minutes} 分（${row.totalMinutes} 分钟）`,
                    },
                    {
                      title: '有效工时',
                      key: 'effective',
                      width: 150,
                      render: (_: unknown, row: OtherScheduleView) =>
                        row.effectiveMinutes == null
                          ? '未核验'
                          : `${row.effectiveMinutes} 分钟${row.corrected ? '（已更正）' : ''}`,
                    },
                    {
                      title: '状态',
                      dataIndex: 'status',
                      width: 90,
                      render: (value: OtherScheduleView['status']) => SCHEDULE_STATUS_LABELS[value],
                    },
                    {
                      title: '操作',
                      key: 'action',
                      width: 200,
                      render: (_: unknown, row: OtherScheduleView) => (
                        <Space size={4}>
                          {row.status === 'PENDING' && (
                            <>
                              <Button
                                type="link"
                                size="small"
                                onClick={() =>
                                  open({ kind: 'scheduleVerify', schedule: row }, { hours: row.hours, minutes: row.minutes })
                                }
                              >
                                核验
                              </Button>
                              <Button
                                type="link"
                                size="small"
                                danger
                                onClick={() =>
                                  open({ kind: 'scheduleCancel', schedule: row }, { reason: undefined })
                                }
                              >
                                取消
                              </Button>
                            </>
                          )}
                          {row.status === 'VERIFIED' && (
                            <Button
                              type="link"
                              size="small"
                              onClick={() =>
                                open(
                                  { kind: 'scheduleCorrect', schedule: row },
                                  { hours: row.hours, minutes: row.minutes, reason: undefined },
                                )
                              }
                            >
                              更正工时
                            </Button>
                          )}
                        </Space>
                      ),
                    },
                  ]}
                />
              </>
            ),
          },
        ]}
      />

      <Modal
        open={modal !== null}
        title={modal ? modalTitle(modal) : ''}
        onCancel={() => setModal(null)}
        onOk={() => void submitModal()}
        confirmLoading={submitting}
        okText="提交"
      >
        {modal && (
          <>
            <Alert type="info" showIcon style={{ marginBottom: 12 }} title={modalHint(modal)} />
            {fieldErrors.length > 0 && (
              <Alert
                type="error"
                showIcon
                style={{ marginBottom: 12 }}
                title="操作未通过，服务端字段错误"
                description={
                  <ul style={{ margin: '4px 0 0', paddingLeft: 20 }}>
                    {fieldErrors.map(entry => (
                      <li key={entry.field}>
                        <b>{entry.field}</b>：{entry.message}
                      </li>
                    ))}
                  </ul>
                }
              />
            )}
            <Form form={form} layout="vertical">
              {modal.kind === 'createPlan' && (
                <>
                  <Form.Item name="orderId" label="订单" rules={[{ required: true, message: '请选择订单' }]}>
                    <Select
                      showSearch
                      optionFilterProp="label"
                      placeholder="选择已确认订单"
                      options={orders.map(order => ({ value: order.id, label: `${order.orderNo} ${order.customerName}` }))}
                      onChange={value => void pickOrder(value)}
                    />
                  </Form.Item>
                  <Form.Item name="orderItemId" label="订单明细" rules={[{ required: true, message: '请选择订单明细' }]}>
                    <Select showSearch optionFilterProp="label" placeholder="选择明细" options={orderItems} />
                  </Form.Item>
                  <Form.Item name="node" label="工序" rules={[{ required: true, message: '请选择工序' }]}>
                    <Select options={NODE_OPTIONS} placeholder="选择工序" />
                  </Form.Item>
                  <Form.Item name="planDate" label="计划日期" rules={[{ required: true, message: '请选择计划日期' }]}>
                    <DatePicker style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="employeeId" label="执行员工" rules={[{ required: true, message: '请选择执行员工' }]}>
                    <Select showSearch optionFilterProp="label" options={employeeOptions} placeholder="选择员工" />
                  </Form.Item>
                  <Form.Item
                    name="quantity"
                    label="计划数量"
                    extra="上限为该工序的「待安排数量」；上游尚不可执行时计划显示「等待上游」，核验时才校验当前可执行数量"
                    rules={[{ required: true, message: '请填写计划数量' }]}
                  >
                    <InputNumber min={1} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="note" label="备注（可空）">
                    <Input />
                  </Form.Item>
                </>
              )}
              {modal.kind === 'overtime' && (
                <>
                  <Form.Item name="orderId" label="订单（本单本工序今天多做）" rules={[{ required: true, message: '请选择订单' }]}>
                    <Select
                      showSearch
                      optionFilterProp="label"
                      placeholder="选择已确认订单"
                      options={orders.map(order => ({ value: order.id, label: `${order.orderNo} ${order.customerName}` }))}
                      onChange={value => void pickOrder(value)}
                    />
                  </Form.Item>
                  <Form.Item name="orderItemId" label="订单明细" rules={[{ required: true, message: '请选择订单明细' }]}>
                    <Select showSearch optionFilterProp="label" placeholder="选择明细" options={orderItems} />
                  </Form.Item>
                  <Form.Item name="node" label="工序" rules={[{ required: true, message: '请选择工序' }]}>
                    <Select options={NODE_OPTIONS} placeholder="选择工序" />
                  </Form.Item>
                  <Form.Item name="futurePlanId" label="来源：未来正常计划" rules={[{ required: true, message: '请选择来源计划' }]}>
                    <Select
                      showSearch
                      optionFilterProp="label"
                      placeholder="选择未来日期的待执行正常计划"
                      options={plans
                        .filter(
                          plan =>
                            plan.planType === 'NORMAL' &&
                            plan.status === 'PENDING' &&
                            dayjs(plan.planDate).isAfter(dayjs(), 'day'),
                        )
                        .map(plan => ({
                          value: plan.id,
                          label: `${plan.planNo} ${plan.planDate} ${NODE_LABELS[plan.node]} 计划 ${plan.quantity}`,
                        }))}
                    />
                  </Form.Item>
                  <Form.Item name="quantity" label="预占数量" rules={[{ required: true, message: '请填写预占数量' }]}>
                    <InputNumber min={1} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="planDate" label="执行日期（只能是今天）" rules={[{ required: true, message: '请选择执行日期' }]}>
                    <DatePicker style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="employeeId" label="执行员工" rules={[{ required: true, message: '请选择执行员工' }]}>
                    <Select showSearch optionFilterProp="label" options={employeeOptions} placeholder="选择员工" />
                  </Form.Item>
                  <Form.Item name="note" label="备注（可空）">
                    <Input />
                  </Form.Item>
                </>
              )}
              {modal.kind === 'cancelPlan' && (
                <Form.Item name="reason" label="取消原因" rules={[{ required: true, message: '请填写取消原因' }]}>
                  <Input />
                </Form.Item>
              )}
              {modal.kind === 'reschedule' && (
                <>
                  <Form.Item name="planDate" label="计划日期" rules={[{ required: true, message: '请选择计划日期' }]}>
                    <DatePicker style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="employeeId" label="执行员工" rules={[{ required: true, message: '请选择执行员工' }]}>
                    <Select showSearch optionFilterProp="label" options={employeeOptions} placeholder="选择员工" />
                  </Form.Item>
                  <Form.Item name="quantity" label="本次安排数量" rules={[{ required: true, message: '请填写数量' }]}>
                    <InputNumber min={1} max={modal.reminder.quantity} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="note" label="备注（可空）">
                    <Input />
                  </Form.Item>
                </>
              )}
              {modal.kind === 'defer' && (
                <Form.Item name="reason" label="原因" rules={[{ required: true, message: '请填写原因' }]}>
                  <Input />
                </Form.Item>
              )}
              {modal.kind === 'reworkSource' && (
                <>
                  <Form.Item name="verificationId" label="原核验 ID" rules={[{ required: true, message: '请填写核验 ID' }]}>
                    <InputNumber min={1} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="targetNode" label="返工目标工序" rules={[{ required: true, message: '请选择目标工序' }]}>
                    <Select options={NODE_OPTIONS} placeholder="选择工序" />
                  </Form.Item>
                  <Form.Item name="quantity" label="返工数量" rules={[{ required: true, message: '请填写数量' }]}>
                    <InputNumber min={1} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="reason" label="原因（可空）">
                    <Input />
                  </Form.Item>
                </>
              )}
              {modal.kind === 'remakeSource' && (
                <>
                  <Form.Item name="verificationId" label="原核验 ID" rules={[{ required: true, message: '请填写核验 ID' }]}>
                    <InputNumber min={1} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="startNode" label="重做起始工序" rules={[{ required: true, message: '请选择起始工序' }]}>
                    <Select options={NODE_OPTIONS} placeholder="选择工序" />
                  </Form.Item>
                  <Form.Item name="quantity" label="重做数量" rules={[{ required: true, message: '请填写数量' }]}>
                    <InputNumber min={1} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="reason" label="原因（从制作开始必填）">
                    <Input />
                  </Form.Item>
                </>
              )}
              {modal.kind === 'sourcePlan' && (
                <>
                  <Form.Item name="planDate" label="计划日期" rules={[{ required: true, message: '请选择计划日期' }]}>
                    <DatePicker style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="employeeId" label="执行员工" rules={[{ required: true, message: '请选择执行员工' }]}>
                    <Select showSearch optionFilterProp="label" options={employeeOptions} placeholder="选择员工" />
                  </Form.Item>
                  <Form.Item name="quantity" label="计划数量" rules={[{ required: true, message: '请填写数量' }]}>
                    <InputNumber min={1} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="note" label="备注（可空）">
                    <Input />
                  </Form.Item>
                </>
              )}
              {modal.kind === 'adjust' && (
                <>
                  <Form.Item
                    name="newQuantity"
                    label="调整后的计划数量（必须大于 0）"
                    rules={[{ required: true, message: '请填写数量' }]}
                  >
                    <InputNumber min={1} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="reason" label="修改原因" rules={[{ required: true, message: '请填写原因' }]}>
                    <Input />
                  </Form.Item>
                </>
              )}
              {modal.kind === 'noAdjust' && (
                <Form.Item name="reason" label="原因" rules={[{ required: true, message: '请填写原因' }]}>
                  <Input />
                </Form.Item>
              )}
              {modal.kind === 'schedule' && (
                <>
                  <Form.Item name="scheduleDate" label="排班日期" rules={[{ required: true, message: '请选择日期' }]}>
                    <DatePicker style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="employeeId" label="员工" rules={[{ required: true, message: '请选择员工' }]}>
                    <Select showSearch optionFilterProp="label" options={employeeOptions} placeholder="选择员工" />
                  </Form.Item>
                  <Form.Item name="hours" label="小时" rules={[{ required: true, message: '请填写小时' }]}>
                    <InputNumber min={0} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="minutes" label="分钟（0–59）" rules={[{ required: true, message: '请填写分钟' }]}>
                    <InputNumber min={0} max={59} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="note" label="备注（可空）">
                    <Input />
                  </Form.Item>
                </>
              )}
              {(modal.kind === 'scheduleVerify' || modal.kind === 'scheduleCorrect') && (
                <>
                  <Form.Item name="hours" label="实际小时" rules={[{ required: true, message: '请填写小时' }]}>
                    <InputNumber min={0} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="minutes" label="实际分钟（0–59）" rules={[{ required: true, message: '请填写分钟' }]}>
                    <InputNumber min={0} max={59} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  {modal.kind === 'scheduleVerify' ? (
                    <Form.Item name="note" label="核验备注（可空）">
                      <Input />
                    </Form.Item>
                  ) : (
                    <Form.Item name="reason" label="更正原因" rules={[{ required: true, message: '请填写原因' }]}>
                      <Input />
                    </Form.Item>
                  )}
                </>
              )}
              {modal.kind === 'scheduleCancel' && (
                <Form.Item name="reason" label="取消原因" rules={[{ required: true, message: '请填写取消原因' }]}>
                  <Input />
                </Form.Item>
              )}
            </Form>
          </>
        )}
      </Modal>
    </Card>
  );
}

function modalTitle(modal: Exclude<ModalState, null>): string {
  switch (modal.kind) {
    case 'cancelPlan':
      return `取消计划 ${modal.plan.planNo}`;
    case 'reschedule':
      return `重新安排未完成 ${modal.reminder.quantity} 件`;
    case 'sourcePlan':
      return `从来源创建计划（${modal.label}）`;
    case 'adjust':
      return `调整未来计划（建议减少 ${modal.reminder.quantity}）`;
    case 'scheduleVerify':
      return `工时核验 ${modal.schedule.scheduleNo}`;
    case 'scheduleCancel':
      return `取消排班 ${modal.schedule.scheduleNo}`;
    case 'scheduleCorrect':
      return `工时更正 ${modal.schedule.scheduleNo}`;
    default:
      return MODAL_TITLES[modal.kind];
  }
}

function modalHint(modal: Exclude<ModalState, null>): string {
  switch (modal.kind) {
    case 'createPlan':
      return '计划数量只能使用「待安排数量」；上游尚不可执行时计划显示「等待上游」，核验时才校验当前可执行数量。';
    case 'cancelPlan':
      return '只有待执行计划可取消；返工/重做计划取消后数量退回来源余额，原计划与来源关系保留。';
    case 'reschedule':
      return '少于未完成余量时余量继续提醒；用满则提醒关闭。';
    case 'defer':
      return '暂不安排只关闭提醒，不删除待安排需求。';
    case 'reworkSource':
      return '目标工序只能取「发现问题工序或其前序」；同一核验的同一目标只有一条来源，总量不得超过该核验的返工数量。';
    case 'remakeSource':
      return '起始工序只能取报废工序或其前序；从制作开始必须填写原因；总量不得超过该核验的报废数量。';
    case 'sourcePlan':
      return '数量不得超过来源余额；员工须具备目标工序的工种资格。';
    case 'overtime':
      return '只能在执行当天创建，来源必须是未来日期的待执行正常计划；预占不修改未来计划原始数量、不产生流入或完成。';
    case 'adjust':
      return '调整会写入计划调整历史（前后数量 + 原因）并同步计划占用；数量必须大于 0，确需归零请改用「取消计划」。';
    case 'noAdjust':
      return '无需调整不改计划，只关闭提醒并留痕。';
    case 'schedule':
      return '小时 + 0–59 分钟，总分钟必须大于 0；不产生商品、库存或订单履约数量。';
    case 'scheduleVerify':
      return '只能核验一次；核验后录错请使用「更正工时」。';
    case 'scheduleCancel':
      return '只有待执行的排班可以取消。';
    case 'scheduleCorrect':
      return '更正只追加事实，原核验不变；有效工时取最新更正。';
    default:
      return '';
  }
}
