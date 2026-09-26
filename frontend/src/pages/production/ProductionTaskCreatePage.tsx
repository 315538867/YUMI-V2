import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  DatePicker,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd';
import { useNavigate } from 'react-router-dom';
import dayjs from 'dayjs';
import {
  createOtherSchedule,
  createOvertimeTask,
  createProductionTask,
  listProductionQuantityReturns,
  listProductionTasks,
  listReworkSources,
  SOURCE_TYPE_LABELS,
  type ProductionQuantityReturnView,
  type ProductionTaskItemView,
  type ProductionTaskView,
  type ReworkSourceView,
} from '../../api/production';
import { getOrder, listOrders, type OrderSummary } from '../../api/orders';
import { listEmployees, type EmployeeView } from '../../api/catalog';
import { listStaticDataItems } from '../../api/staticData';
import { NODE_LABELS } from '../../api/inventory';
import { describeApiError, YumiApiError } from '../../api/errors';
import { CREATE_TYPE_OPTIONS, type CreateScheduleType } from './productionLogic';

export interface WorkTypeOption {
  value: number;
  label: string;
  code: string;
}

export interface OrderItemOption {
  value: number;
  label: string;
  productId: number;
  productNo: string;
  productName: string;
  quantity: number;
  seamQuantity: number;
}

/** 明细上下文视图：全部为服务端返回值，前端只展示、不推导权威数量。 */
export interface ProductionItemContextView {
  orderNo?: string | null;
  lineNo?: number;
  productNo?: string;
  productName?: string;
  node?: string;
  plannedQuantity?: number;
  standardMinutes?: number | null;
  estimatedMinutes?: number | null;
  actualInflow?: number;
  executableQuantity?: number;
  remainingNormalQuantity?: number | null;
  sourceLabel?: string | null;
  sourceBalance?: number | null;
  returnBalance?: number | null;
  capacityNotice?: string | null;
}

/**
 * 明细上下文面板（阶段五 5.17）：把「计划数量 / 实际流入 / 当前可执行 / 回转余额」分栏展示，
 * 数值一律来自服务端；标准分钟与估算分钟由服务端在创建时冻结，前端不计算。
 */
export function ProductionItemContextPanel({ context }: { context: ProductionItemContextView }) {
  return (
    <Card size="small" style={{ background: '#fafafa' }}>
      <Space orientation="vertical" size={4} style={{ width: '100%' }}>
        <Typography.Text>
          {context.orderNo ?? '订单'} · 明细 #{context.lineNo ?? '-'} · {context.productNo} {context.productName}
          {context.node ? ` · 工序 ${NODE_LABELS[context.node as keyof typeof NODE_LABELS] ?? context.node}` : ''}
        </Typography.Text>
        <Row gutter={12}>
          <Col span={6}>
            <Typography.Text type="secondary">计划数量</Typography.Text>
            <Typography.Title level={5} style={{ margin: 0 }}>
              {context.plannedQuantity ?? '—'}
            </Typography.Title>
          </Col>
          <Col span={6}>
            <Typography.Text type="secondary">实际流入</Typography.Text>
            <Typography.Title level={5} style={{ margin: 0 }}>
              {context.actualInflow ?? '—'}
            </Typography.Title>
          </Col>
          <Col span={6}>
            <Typography.Text type="secondary">当前可执行</Typography.Text>
            <Typography.Title level={5} style={{ margin: 0 }}>
              {context.executableQuantity ?? '—'}
            </Typography.Title>
          </Col>
          <Col span={6}>
            <Typography.Text type="secondary">回转余额</Typography.Text>
            <Typography.Title level={5} style={{ margin: 0 }}>
              {context.returnBalance ?? context.remainingNormalQuantity ?? '—'}
            </Typography.Title>
          </Col>
        </Row>
        <Space size={12} wrap>
          <Typography.Text type="secondary">标准分钟（服务端冻结）{context.standardMinutes ?? '—'}</Typography.Text>
          <Typography.Text type="secondary">估算分钟（服务端冻结）{context.estimatedMinutes ?? '—'}</Typography.Text>
          {context.sourceLabel && (
            <Tag color="cyan">
              来源 {context.sourceLabel} 余额 {context.sourceBalance ?? '—'}
            </Tag>
          )}
          {context.capacityNotice && <Tag color="orange">{context.capacityNotice}</Tag>}
        </Space>
      </Space>
    </Card>
  );
}

interface ItemDraft {
  orderId?: number;
  orderItemId?: number;
  sourceType: string;
  sourceId?: number;
  plannedQuantity?: number;
  futureTaskItemId?: number;
}

export interface ProductionTaskCreatePageProps {
  initialEmployees?: EmployeeView[];
  initialOrders?: OrderSummary[];
  initialWorkTypes?: WorkTypeOption[];
  /** 测试注入：直接以某类型渲染表单，验证 OTHER 的分钟字段与明细区块结构。 */
  initialScheduleType?: CreateScheduleType;
}

/**
 * 全页统一新建（阶段五 5.17，对齐原型 `.type-row` / `.context-fields` / `.create-grid`）：
 * 一个入口，在表单内选择 NORMAL / REWORK / 超额预占 / OTHER；左侧表单 + 310px 右侧摘要栏。
 * 来源与余额只在明细上下文展示；前端不计算权威数量（计划/流入/可执行/估算分钟均取服务端）。
 */
export function ProductionTaskCreatePage({
  initialEmployees,
  initialOrders,
  initialWorkTypes,
  initialScheduleType,
}: ProductionTaskCreatePageProps = {}) {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [scheduleType, setScheduleType] = useState<CreateScheduleType>(initialScheduleType ?? 'NORMAL');
  const [taskDate, setTaskDate] = useState<string>(dayjs().format('YYYY-MM-DD'));
  const [employeeId, setEmployeeId] = useState<number>();
  const [workTypeId, setWorkTypeId] = useState<number>();
  const [note, setNote] = useState('');
  const [hours, setHours] = useState<number>(1);
  const [minutes, setMinutes] = useState<number>(0);

  const [employees, setEmployees] = useState<EmployeeView[]>(initialEmployees ?? []);
  const [orders, setOrders] = useState<OrderSummary[]>(initialOrders ?? []);
  const [workTypes, setWorkTypes] = useState<WorkTypeOption[]>(initialWorkTypes ?? []);
  const [orderItemsByOrder, setOrderItemsByOrder] = useState<Record<number, OrderItemOption[]>>({});
  const [reworkSources, setReworkSources] = useState<ReworkSourceView[]>([]);
  const [returns, setReturns] = useState<ProductionQuantityReturnView[]>([]);
  const [futureItems, setFutureItems] = useState<ProductionTaskItemView[]>([]);
  const [items, setItems] = useState<ItemDraft[]>([{ sourceType: 'ORDER' }]);
  const [fieldErrors, setFieldErrors] = useState<{ field: string; message: string }[]>([]);
  const [submitting, setSubmitting] = useState(false);

  const workTypeCode = workTypes.find(option => option.value === workTypeId)?.code;

  useEffect(() => {
    (async () => {
      try {
        const [employeeList, orderList, workTypeList] = await Promise.all([
          listEmployees({ status: 'ACTIVE' }),
          listOrders({ status: 'CONFIRMED' }),
          listStaticDataItems('WORK_TYPE'),
        ]);
        setEmployees(employeeList);
        setOrders(orderList);
        setWorkTypes(
          workTypeList
            .filter(item => ['MAKING', 'PACKING_BAG', 'SEAM_CUTTING'].includes(item.code ?? ''))
            .map(item => ({ value: item.id, label: item.name, code: item.code ?? '' })),
        );
      } catch (error) {
        message.error(describeApiError(error));
      }
    })();
  }, []);

  // 超额预占：来源只能是未来日期的 NORMAL 待执行明细（服务端解析订单/产品/工序）。
  useEffect(() => {
    if (scheduleType !== 'OVERTIME') {
      return;
    }
    (async () => {
      try {
        const list = await listProductionTasks({
          dateFrom: dayjs().add(1, 'day').format('YYYY-MM-DD'),
          taskType: 'NORMAL',
        });
        setFutureItems(list.flatMap(task => task.items.filter(item => item.status === 'PENDING')));
      } catch (error) {
        message.error(describeApiError(error));
      }
    })();
  }, [scheduleType]);

  const employeeOptions = useMemo(
    () => employees.map(entry => ({ value: entry.id!, label: `${entry.employeeNo} ${entry.name}` })),
    [employees],
  );

  // 返工：来源余额按所选工序（发生工序内部返工）从服务端读取，供明细上下文选择。
  useEffect(() => {
    if (scheduleType !== 'REWORK') {
      return;
    }
    (async () => {
      try {
        setReworkSources(await listReworkSources({ node: workTypeCode }));
      } catch (error) {
        message.error(describeApiError(error));
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scheduleType, workTypeCode]);

  async function pickOrder(index: number, orderId: number) {
    setItems(previous => previous.map((item, i) => (i === index ? { ...item, orderId, orderItemId: undefined } : item)));
    if (!orderItemsByOrder[orderId]) {
      try {
        const order = await getOrder(orderId);
        setOrderItemsByOrder(previous => ({
          ...previous,
          [orderId]: order.items.map(item => ({
            value: item.id,
            label: `#${item.lineNo} ${item.productNo} ${item.productName}（订购 ${item.quantity} / 缝边 ${item.seamQuantity}）`,
            productId: item.productId,
            productNo: item.productNo,
            productName: item.productName,
            quantity: item.quantity,
            seamQuantity: item.seamQuantity,
          })),
        }));
      } catch (error) {
        message.error(describeApiError(error));
      }
    }
  }

  /** 选订单明细后按需拉取来源/回转余额（只读 GET），在明细上下文展示服务端余额。 */
  async function pickOrderItem(index: number, orderItemId: number) {
    setItems(previous => previous.map((item, i) => (i === index ? { ...item, orderItemId } : item)));
    if (scheduleType !== 'NORMAL' && scheduleType !== 'REWORK') {
      return;
    }
    try {
      const [sourceList, returnList] = await Promise.all([
        listReworkSources({ orderItemId }),
        listProductionQuantityReturns({ orderItemId, node: workTypeCode }),
      ]);
      setReworkSources(sourceList);
      setReturns(returnList);
    } catch (error) {
      message.error(describeApiError(error));
    }
  }

  function updateItem(index: number, patch: Partial<ItemDraft>) {
    setItems(previous => previous.map((item, i) => (i === index ? { ...item, ...patch } : item)));
  }

  function submitPayload() {
    if (scheduleType === 'OTHER') {
      return {
        scheduleDate: taskDate,
        employeeId: employeeId!,
        hours,
        minutes,
        note,
      };
    }
    if (scheduleType === 'OVERTIME') {
      return {
        taskDate: dayjs().format('YYYY-MM-DD'),
        employeeId: employeeId!,
        note,
        items: items.map(item => ({
          futureTaskItemId: item.futureTaskItemId!,
          plannedQuantity: item.plannedQuantity ?? 0,
        })),
      };
    }
    return {
      taskDate,
      employeeId: employeeId!,
      workTypeId: workTypeId!,
      taskType: scheduleType,
      note,
      items: items.map(item => ({
        orderItemId: item.orderItemId!,
        plannedQuantity: item.plannedQuantity ?? 0,
        sourceType: item.sourceType,
        sourceId: item.sourceId,
      })),
    };
  }

  async function submit() {
    setSubmitting(true);
    setFieldErrors([]);
    try {
      if (scheduleType === 'OTHER') {
        await createOtherSchedule(submitPayload() as Parameters<typeof createOtherSchedule>[0]);
        message.success('其他排班已创建');
      } else if (scheduleType === 'OVERTIME') {
        await createOvertimeTask(submitPayload() as Parameters<typeof createOvertimeTask>[0]);
        message.success('超额预占已创建');
      } else {
        const created = await createProductionTask(submitPayload() as Parameters<typeof createProductionTask>[0]);
        message.success('排班已创建');
        navigate(`/production/tasks/${created.id}`);
        return;
      }
      navigate('/production');
    } catch (error) {
      if (error instanceof YumiApiError) {
        setFieldErrors(error.fieldErrors);
      }
      message.error(describeApiError(error));
    } finally {
      setSubmitting(false);
    }
  }

  const isItemForm = scheduleType === 'NORMAL' || scheduleType === 'REWORK';
  const selectedEmployee = employees.find(entry => entry.id === employeeId);
  const selectedWorkType = workTypes.find(option => option.value === workTypeId);

  return (
    <div className="production-page">
      <Button type="link" className="back" onClick={() => navigate('/production')}>
        ← 返回工作台
      </Button>
      <div className="page-heading">
        <div>
          <Typography.Title level={4} style={{ margin: '0 0 6px' }}>
            新建排班
          </Typography.Title>
          <Space size={8} wrap>
            <Tag color="blue">统一入口</Tag>
            <Typography.Text type="secondary">
              在同一个表单内选择任务类型；来源、额度和可执行余额在明细上下文中解释展示，不作为前置导航。
            </Typography.Text>
          </Space>
        </div>
      </div>

      {fieldErrors.length > 0 && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 12 }}
          title="提交未通过，服务端字段错误"
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

      <Card>
        <div className="type-row">
          <span>
            <Typography.Text type="secondary">排班类型</Typography.Text>
            <br />
            <Select
              style={{ width: 340 }}
              value={scheduleType}
              aria-label="排班类型"
              options={CREATE_TYPE_OPTIONS}
              onChange={value => setScheduleType(value)}
            />
          </span>
          <Typography.Text type="secondary">
            四种类型都在这一个入口内选择，不提供分散的主创建按钮。
          </Typography.Text>
        </div>

        <div
          className="context-fields"
          style={{ display: 'grid', gridTemplateColumns: '1fr 1.35fr 0.8fr' }}
        >
          <div>
            <label htmlFor="create-employee">执行员工</label>
            <Select
              id="create-employee"
              showSearch
              optionFilterProp="label"
              placeholder="选择员工"
              value={employeeId}
              options={employeeOptions}
              onChange={setEmployeeId}
            />
          </div>
          <div>
            <label htmlFor="create-worktype">工序</label>
            <Select
              id="create-worktype"
              placeholder="选择工序"
              disabled={!isItemForm}
              value={workTypeId}
              options={workTypes.map(option => ({ value: option.value, label: option.label }))}
              onChange={setWorkTypeId}
            />
          </div>
          <div>
            <label htmlFor="create-date">
              {scheduleType === 'OVERTIME' ? '执行日期（只能是今天）' : scheduleType === 'OTHER' ? '排班日期' : '任务日期'}
            </label>
            <DatePicker
              id="create-date"
              style={{ width: '100%' }}
              value={dayjs(taskDate)}
              disabled={scheduleType === 'OVERTIME'}
              onChange={value => setTaskDate(value ? value.format('YYYY-MM-DD') : dayjs().format('YYYY-MM-DD'))}
            />
          </div>
        </div>

        <div
          className="create-grid"
          style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 310px' }}
        >
          <div>
            {scheduleType === 'OTHER' ? (
              <div className="allocation">
                <div className="table-caption">
                  <Typography.Text strong>工时（分钟事实，不产生商品数量）</Typography.Text>
                  <Typography.Text type="secondary">只保存总分钟</Typography.Text>
                </div>
                <div className="time-fields">
                  <span>
                    <Typography.Text type="secondary">小时</Typography.Text>
                    <br />
                    <InputNumber min={0} precision={0} value={hours} onChange={value => setHours(value ?? 0)} />
                  </span>
                  <span>
                    <Typography.Text type="secondary">分钟（0–59）</Typography.Text>
                    <br />
                    <InputNumber
                      min={0}
                      max={59}
                      precision={0}
                      value={minutes}
                      onChange={value => setMinutes(value ?? 0)}
                    />
                  </span>
                </div>
                <div style={{ marginTop: 18 }}>
                  <Typography.Text type="secondary">备注</Typography.Text>
                  <br />
                  <Input value={note} onChange={event => setNote(event.target.value)} />
                </div>
              </div>
            ) : items.length === 0 ? (
              <div className="empty-context">
                <Typography.Text type="secondary">
                  已删除全部明细；点击右侧「添加明细」重新选择订单明细或超额来源。
                </Typography.Text>
              </div>
            ) : (
              <>
                <div className="table-caption">
                  <Typography.Text strong>任务明细（可多订单多产品）</Typography.Text>
                  <Typography.Text type="secondary">来源与余额取自服务端，前端不计算权威数量</Typography.Text>
                </div>
                {items.map((item, index) => (
                  <Card key={index} size="small" className="allocation" title={`明细 #${index + 1}`}>
                    <Space orientation="vertical" size={8} style={{ width: '100%' }}>
                      <Space size={8} wrap>
                        {scheduleType === 'NORMAL' && (
                          <>
                            <Select
                              style={{ width: 200 }}
                              showSearch
                              optionFilterProp="label"
                              placeholder="订单"
                              value={item.orderId}
                              options={orders.map(order => ({
                                value: order.id,
                                label: `${order.orderNo} ${order.customerName}`,
                              }))}
                              onChange={value => void pickOrder(index, value)}
                            />
                            <Select
                              style={{ width: 300 }}
                              showSearch
                              optionFilterProp="label"
                              placeholder="订单明细"
                              value={item.orderItemId}
                              options={item.orderId ? orderItemsByOrder[item.orderId] ?? [] : []}
                              onChange={value => void pickOrderItem(index, value)}
                            />
                            <Select
                              style={{ width: 160 }}
                              value={item.sourceType}
                              aria-label="来源类型"
                              options={Object.entries(SOURCE_TYPE_LABELS)
                                .filter(([value]) => value === 'ORDER' || value === 'QUANTITY_RETURN')
                                .map(([value, label]) => ({ value, label }))}
                              onChange={value => updateItem(index, { sourceType: value, sourceId: undefined })}
                            />
                            {item.sourceType === 'QUANTITY_RETURN' && (
                              <Select
                                style={{ width: 220 }}
                                placeholder="回转余额来源"
                                value={item.sourceId}
                                options={returns.map(entry => ({
                                  value: entry.id,
                                  label: `回转 #${entry.id} 可分配 ${entry.availableQuantity}`,
                                }))}
                                onChange={value => updateItem(index, { sourceId: value })}
                              />
                            )}
                          </>
                        )}
                        {scheduleType === 'REWORK' && (
                          <Select
                            style={{ width: 360 }}
                            showSearch
                            optionFilterProp="label"
                            placeholder="返工来源（工序内部）"
                            value={item.sourceId}
                            options={reworkSources.map(source => ({
                              value: source.id,
                              label: `来源 ${source.sourceNo} 工序 ${source.node} 可安排 ${source.availableQuantity}（第 ${source.roundNo} 轮）`,
                            }))}
                            onChange={value => {
                              const source = reworkSources.find(entry => entry.id === value);
                              updateItem(index, {
                                sourceId: value,
                                sourceType: 'REWORK_SOURCE',
                                orderItemId: source?.orderItemId,
                              });
                            }}
                          />
                        )}
                        {scheduleType === 'OVERTIME' && (
                          <Select
                            style={{ width: 420 }}
                            showSearch
                            optionFilterProp="label"
                            placeholder="来源：未来日期的正常待执行明细"
                            value={item.futureTaskItemId}
                            options={futureItems.map(future => ({
                              value: future.id,
                              label: `${future.orderNo} #${future.lineNo} ${future.productName} ${future.node} 计划 ${future.plannedQuantity}`,
                            }))}
                            onChange={value => updateItem(index, { futureTaskItemId: value })}
                          />
                        )}
                        <InputNumber
                          min={1}
                          precision={0}
                          placeholder="计划数量"
                          value={item.plannedQuantity}
                          onChange={value => updateItem(index, { plannedQuantity: value ?? undefined })}
                        />
                      </Space>

                      {isItemForm && item.orderItemId && (
                        <ProductionItemContextPanel
                          context={{
                            orderNo: orders.find(order => order.id === item.orderId)?.orderNo,
                            productNo: item.orderId
                              ? orderItemsByOrder[item.orderId]?.find(option => option.value === item.orderItemId)
                                  ?.productNo
                              : undefined,
                            productName: item.orderId
                              ? orderItemsByOrder[item.orderId]?.find(option => option.value === item.orderItemId)
                                  ?.productName
                              : undefined,
                            node: workTypeCode,
                            plannedQuantity: item.plannedQuantity,
                            sourceLabel: SOURCE_TYPE_LABELS[item.sourceType] ?? item.sourceType,
                            sourceBalance:
                              item.sourceType === 'REWORK_SOURCE'
                                ? reworkSources.find(source => source.id === item.sourceId)?.availableQuantity
                                : undefined,
                            returnBalance:
                              item.sourceType === 'QUANTITY_RETURN'
                                ? returns.find(entry => entry.id === item.sourceId)?.availableQuantity
                                : undefined,
                          }}
                        />
                      )}
                    </Space>
                  </Card>
                ))}
              </>
            )}
          </div>

          <Card size="small" title="本次排班">
            <div className="context-name">
              <Typography.Text type="secondary">
                类型：{CREATE_TYPE_OPTIONS.find(option => option.value === scheduleType)?.label ?? scheduleType}
              </Typography.Text>
              <Typography.Text type="secondary">
                日期：{scheduleType === 'OVERTIME' ? dayjs().format('YYYY-MM-DD') : taskDate}
              </Typography.Text>
              <Typography.Text type="secondary">
                员工：{selectedEmployee ? `${selectedEmployee.employeeNo} ${selectedEmployee.name}` : '未选择'}
              </Typography.Text>
              <Typography.Text type="secondary">
                工序：{isItemForm ? selectedWorkType?.label ?? '未选择' : '不适用'}
              </Typography.Text>
              <Typography.Text type="secondary">明细：{isItemForm ? `${items.length} 条` : '不适用'}</Typography.Text>
            </div>
            <div className="allocation-actions">
              {scheduleType !== 'OTHER' && (
                <Button
                  onClick={() =>
                    setItems(previous => [
                      ...previous,
                      { sourceType: scheduleType === 'REWORK' ? 'REWORK_SOURCE' : 'ORDER' },
                    ])
                  }
                >
                  添加明细
                </Button>
              )}
              <Button type="primary" loading={submitting} onClick={() => void submit()}>
                提交
              </Button>
              <Button onClick={() => navigate('/production')}>取消</Button>
              <Button onClick={() => navigate('/production')}>返回工作台</Button>
            </div>
          </Card>
        </div>
      </Card>
    </div>
  );
}
