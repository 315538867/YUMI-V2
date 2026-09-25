import { useEffect, useState } from 'react';
import { Alert, App, Button, DatePicker, Form, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import {
  AFTER_SALES_SOURCE_PURPOSE_LABELS,
  AFTER_SALES_STATUS_LABELS,
  AFTER_SALES_TYPE_LABELS,
  allocateAfterSalesInventory,
  confirmReplacementShipment,
  correctAfterSales,
  createAfterSales,
  createAfterSalesProductionPlan,
  createReplacementShipment,
  listAfterSales,
  listAfterSalesProductionSources,
  verifyAfterSalesReturn,
  type AfterSalesCaseView,
  type AfterSalesItemView,
  type AfterSalesSourceView,
} from '../../api/afterSales';
import { listShipments, SHIPMENT_STATUS_LABELS, type ShipmentView } from '../../api/shipments';
import { listBatches, NODE_LABELS, type InventoryBatchView } from '../../api/inventory';
import { listEmployees, type EmployeeView } from '../../api/catalog';
import type { OrderDetail } from '../../api/orders';
import { describeApiError, YumiApiError } from '../../api/errors';
import { confirmedSourceOptions, productionPlanPayload } from './afterSalesInput';

type ModalState =
  | { kind: 'create' }
  | { kind: 'verifyReturn'; caseId: number; item: AfterSalesItemView }
  | { kind: 'inventory'; caseId: number; item: AfterSalesItemView }
  | { kind: 'production'; caseId: number; item: AfterSalesItemView }
  | { kind: 'replacement'; caseId: number; item: AfterSalesItemView }
  | { kind: 'correct'; caseId: number; item: AfterSalesItemView }
  | null;

const PRODUCTION_NODE_LABELS: Record<string, string> = {
  MAKING: NODE_LABELS.MAKING,
  PACKING_BAG: NODE_LABELS.PACKING_BAG,
  SEAM_CUTTING: NODE_LABELS.SEAM_CUTTING,
};

const TITLES: Record<Exclude<ModalState, null>['kind'], string> = {
  create: '创建售后',
  verifyReturn: '退回核验',
  inventory: '售后库存补发领用',
  production: '售后生产补发计划',
  replacement: '创建补发发货批次',
  correct: '售后更正',
};

const HINTS: Record<Exclude<ModalState, null>['kind'], string> = {
  create: '售后必须引用已确认且有效的发货批次明细；受理数量不得超过该明细的有效已发数量，同一发货明细只允许一个有效售后占用。',
  verifyReturn: '退回数量必须等于售后返工 + 售后报废；退回不自动入库、不恢复原发货库存。',
  inventory: '售后补发只能领用成品批次（可发货）：扣库存只发生一次，且只增加售后可补发，不直接增加已补发。',
  production:
    '售后返工按退回核验的返工数量排产，售后补发生产只为「补发需求 − 已补发 − 可补发」的缺口排产；合格只增加售后可补发，不进入订单履约、也不自动进入通用库存。',
  replacement: '补发批次先创建为草稿（不占用可补发），确认时才校验「本次 ≤ 可补发」与「已补发 + 本次 ≤ 补发需求」。',
  correct: '更正只追加事实，原退回核验保持不变（首期只支持更正退回核验）。',
};

/**
 * 订单详情「发货与售后」Tab 的售后区域（任务 8.10）：
 * 按售后单只读展示来源批次、受理/退回/补发需求、退回核验、可补发/已补发/待补发与退款；
 * 创建售后、退回核验、库存补发、补发发货与更正都由显式按钮进入操作，不提供顶级售后工作区。
 */
export function AfterSalesPanel({ order }: { order: OrderDetail }) {
  const { message } = App.useApp();
  const [form] = Form.useForm();
  const [cases, setCases] = useState<AfterSalesCaseView[]>([]);
  const [shipments, setShipments] = useState<ShipmentView[]>([]);
  const [batches, setBatches] = useState<InventoryBatchView[]>([]);
  const [employees, setEmployees] = useState<EmployeeView[]>([]);
  const [productionSources, setProductionSources] = useState<AfterSalesSourceView[]>([]);
  const [loading, setLoading] = useState(false);
  const [modal, setModal] = useState<ModalState>(null);
  const [submitting, setSubmitting] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<{ field: string; message: string }[]>([]);

  async function reload() {
    setLoading(true);
    try {
      const [caseList, shipmentList, batchList, employeeList] = await Promise.all([
        listAfterSales(order.id),
        listShipments(order.id),
        listBatches({ node: 'SHIPPABLE' }),
        listEmployees({ status: 'ACTIVE' }),
      ]);
      setCases(caseList);
      setShipments(shipmentList);
      setBatches(batchList);
      setEmployees(employeeList);
      const sources = await Promise.all(
        caseList.map(salesCase => listAfterSalesProductionSources(salesCase.id)),
      );
      setProductionSources(sources.flat());
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void reload();
  }, [order.id, order.version]);

  /** 可作为售后来源的明细：只列已确认批次（口径与测试见 afterSalesInput.ts）。 */
  const sourceOptions = confirmedSourceOptions(shipments);

  function open(next: Exclude<ModalState, null>, initial: Record<string, unknown> = {}) {
    form.resetFields();
    setFieldErrors([]);
    form.setFieldsValue(initial);
    setModal(next);
  }

  async function submitModal() {
    if (!modal) {
      return;
    }
    const values = await form.validateFields();
    setSubmitting(true);
    setFieldErrors([]);
    try {
      if (modal.kind === 'create') {
        await createAfterSales(order.id, {
          caseType: values.caseType,
          problem: values.problem,
          solution: values.solution,
          note: values.note,
          items: [
            {
              shipmentItemId: values.shipmentItemId,
              acceptedQuantity: values.acceptedQuantity,
              returnedQuantity: values.returnedQuantity ?? 0,
              replacementRequiredQuantity: values.replacementRequiredQuantity ?? 0,
            },
          ],
        });
      } else if (modal.kind === 'verifyReturn') {
        await verifyAfterSalesReturn(modal.caseId, {
          afterSalesItemId: modal.item.id,
          returnedQuantity: values.returnedQuantity,
          reworkQuantity: values.reworkQuantity,
          scrapQuantity: values.scrapQuantity,
          reason: values.reason,
        });
      } else if (modal.kind === 'inventory') {
        await allocateAfterSalesInventory({
          afterSalesItemId: modal.item.id,
          batchId: values.batchId,
          quantity: values.quantity,
          reason: values.reason,
        });
      } else if (modal.kind === 'production') {
        await createAfterSalesProductionPlan(
          modal.caseId,
          productionPlanPayload(values, modal.item.id),
        );
      } else if (modal.kind === 'replacement') {
        const updated = await createReplacementShipment(modal.caseId, {
          items: [{ afterSalesItemId: modal.item.id, quantity: values.quantity }],
        });
        const shipmentId = updated.replacementShipmentIds.at(-1);
        if (shipmentId) {
          await confirmReplacementShipment(modal.caseId, shipmentId);
        }
      } else {
        await correctAfterSales(modal.caseId, {
          targetType: 'RETURN_VERIFICATION',
          targetId: modal.item.id,
          beforeValue: String(modal.item.returnedQuantity),
          afterValue: values.afterValue,
          reason: values.reason,
        });
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

  return (
    <>
      <Space style={{ marginBottom: 8 }} wrap>
        <Typography.Text strong>售后台账</Typography.Text>
        <Button size="small" type="primary" disabled={order.status === 'DRAFT'} onClick={() => open({ kind: 'create' }, { caseType: 'REWORK_AND_REPLACEMENT' })}>
          创建售后
        </Button>
        <Button size="small" onClick={() => void reload()} loading={loading}>
          刷新
        </Button>
      </Space>
      <Table<AfterSalesCaseView>
        size="small"
        rowKey="id"
        loading={loading}
        dataSource={cases}
        pagination={false}
        locale={{ emptyText: '暂无售后单；售后必须引用已确认发货批次明细' }}
        expandable={{
          expandedRowRender: salesCase => {
            const caseSources = productionSources.filter(source =>
              salesCase.items.some(item => item.id === source.afterSalesItemId),
            );
            return (
            <>
              <Table<AfterSalesItemView>
                size="small"
                rowKey="id"
                pagination={false}
                dataSource={salesCase.items}
                columns={[
                  {
                    title: '来源批次明细',
                    key: 'source',
                    render: (_, row) => `${row.shipmentNo ?? '—'} #${row.productNo} ${row.productName}`,
                  },
                  { title: '受理', dataIndex: 'acceptedQuantity', width: 70, align: 'right' },
                  { title: '退回', dataIndex: 'returnedQuantity', width: 70, align: 'right' },
                  { title: '补发需求', dataIndex: 'replacementRequiredQuantity', width: 90, align: 'right' },
                  {
                    title: '退回核验',
                    key: 'verification',
                    width: 150,
                    render: (_, row) =>
                      row.returnVerified ? (
                        <Space size={4}>
                          <Tag color="blue">返工 {row.reworkQuantity}</Tag>
                          <Tag color="red">报废 {row.scrapQuantity}</Tag>
                        </Space>
                      ) : (
                        <Tag>未核验</Tag>
                      ),
                  },
                  { title: '可补发', dataIndex: 'availableQuantity', width: 80, align: 'right' },
                  { title: '已补发', dataIndex: 'shippedQuantity', width: 80, align: 'right' },
                  { title: '待补发', dataIndex: 'pendingQuantity', width: 80, align: 'right' },
                  {
                    title: '操作',
                    key: 'action',
                    width: 320,
                    render: (_, row) => (
                      <Space size={4} wrap>
                        <Button
                          type="link"
                          size="small"
                          disabled={row.returnVerified}
                          onClick={() =>
                            open(
                              { kind: 'verifyReturn', caseId: salesCase.id, item: row },
                              {
                                returnedQuantity: row.returnedQuantity || row.acceptedQuantity,
                                reworkQuantity: 0,
                                scrapQuantity: 0,
                              },
                            )
                          }
                        >
                          退回核验
                        </Button>
                        <Button
                          type="link"
                          size="small"
                          onClick={() =>
                            open({ kind: 'inventory', caseId: salesCase.id, item: row }, { quantity: row.pendingQuantity || 1 })
                          }
                        >
                          库存补发
                        </Button>
                        <Button
                          type="link"
                          size="small"
                          disabled={!row.returnVerified}
                          onClick={() =>
                            open({ kind: 'production', caseId: salesCase.id, item: row }, { purpose: 'REWORK', node: 'MAKING', planDate: undefined, employeeId: undefined, quantity: row.reworkQuantity ?? 1 })
                          }
                        >
                          生产补发
                        </Button>
                        <Button
                          type="link"
                          size="small"
                          disabled={row.pendingQuantity <= 0}
                          onClick={() =>
                            open({ kind: 'replacement', caseId: salesCase.id, item: row }, { quantity: row.pendingQuantity })
                          }
                        >
                          补发发货
                        </Button>
                        <Button
                          type="link"
                          size="small"
                          disabled={!row.returnVerified}
                          onClick={() => open({ kind: 'correct', caseId: salesCase.id, item: row }, { afterValue: undefined, reason: undefined })}
                        >
                          更正
                        </Button>
                      </Space>
                    ),
                  },
                ]}
              />
              {salesCase.corrections.length > 0 && (
                <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
                  更正记录 {salesCase.corrections.length} 条（原退回核验保持不变）。
                </Typography.Paragraph>
              )}
              {salesCase.refunds.length > 0 && (
                <Typography.Paragraph type="secondary" style={{ margin: '8px 0 0' }}>
                  售后退款 {salesCase.refunds.length} 笔（单列于累计实际净收，不冲减订单结清净额）。
                </Typography.Paragraph>
              )}
              {caseSources.length > 0 && (
                <Typography.Paragraph type="secondary" style={{ margin: '8px 0 0' }}>
                  生产补发来源：
                  {caseSources
                    .map(
                      source =>
                        `${AFTER_SALES_SOURCE_PURPOSE_LABELS[source.purpose] ?? source.purpose}·${
                          PRODUCTION_NODE_LABELS[source.node] ?? source.node
                        } 额度 ${source.totalQuantity}，已安排 ${source.arrangedQuantity}，余额 ${source.balance}`,
                    )
                    .join('；')}
                  （合格只增加可补发，不进入订单履约、也不自动进入通用库存）
                </Typography.Paragraph>
              )}
              <Typography.Paragraph type="secondary" style={{ margin: '8px 0 0' }}>
                售后独立于原订单履约：补发不改变原订单订购数量、累计发货、未交付需求、应收与主状态。
              </Typography.Paragraph>
            </>
            );
          },
        }}
        columns={[
          { title: '售后单号', dataIndex: 'caseNo', width: 110 },
          {
            title: '类型',
            dataIndex: 'caseType',
            width: 110,
            render: (value: string) => AFTER_SALES_TYPE_LABELS[value] ?? value,
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 90,
            render: (value: string) => <Tag>{AFTER_SALES_STATUS_LABELS[value] ?? value}</Tag>,
          },
          { title: '问题', dataIndex: 'problem' },
          { title: '明细数', key: 'lines', width: 80, render: (_, row) => row.items.length },
        ]}
      />
      {fieldErrors.length > 0 && (
        <Alert
          style={{ marginTop: 12 }}
          type="error"
          showIcon
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

      <Modal
        open={modal !== null}
        title={modal ? TITLES[modal.kind] : ''}
        onCancel={() => setModal(null)}
        onOk={() => void submitModal()}
        confirmLoading={submitting}
        okText="提交"
      >
        {modal && (
          <>
            <Alert type="info" showIcon style={{ marginBottom: 12 }} title={HINTS[modal.kind]} />
            <Form form={form} layout="vertical">
              {modal.kind === 'create' && (
                <>
                  <Form.Item name="caseType" label="售后类型" rules={[{ required: true, message: '请选择类型' }]}>
                    <Select
                      options={Object.entries(AFTER_SALES_TYPE_LABELS).map(([value, label]) => ({ value, label }))}
                    />
                  </Form.Item>
                  <Form.Item name="shipmentItemId" label="来源（已确认发货批次明细）" rules={[{ required: true, message: '请选择来源明细' }]}>
                    <Select showSearch optionFilterProp="label" options={sourceOptions} placeholder="仅已确认批次可选" />
                  </Form.Item>
                  <Form.Item name="acceptedQuantity" label="受理数量" rules={[{ required: true, message: '请填写受理数量' }]}>
                    <InputNumber min={1} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="returnedQuantity" label="退回数量（可空）">
                    <InputNumber min={0} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="replacementRequiredQuantity" label="补发需求数量（可空）">
                    <InputNumber min={0} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="problem" label="问题描述" rules={[{ required: true, message: '请填写问题描述' }]}>
                    <Input />
                  </Form.Item>
                  <Form.Item name="solution" label="处理方案（可空）">
                    <Input />
                  </Form.Item>
                </>
              )}
              {modal.kind === 'verifyReturn' && (
                <>
                  <Form.Item name="returnedQuantity" label="客户退回数量" rules={[{ required: true, message: '请填写退回数量' }]}>
                    <InputNumber min={0} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="reworkQuantity" label="售后返工数量" rules={[{ required: true, message: '请填写返工数量' }]}>
                    <InputNumber min={0} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="scrapQuantity" label="售后报废数量" rules={[{ required: true, message: '请填写报废数量' }]}>
                    <InputNumber min={0} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="reason" label="原因（可空）">
                    <Input />
                  </Form.Item>
                </>
              )}
              {modal.kind === 'inventory' && (
                <>
                  <Form.Item name="batchId" label="成品批次（可发货）" rules={[{ required: true, message: '请选择批次' }]}>
                    <Select
                      showSearch
                      optionFilterProp="label"
                      options={batches.map(batch => ({
                        value: batch.id,
                        label: `${batch.batchNo} ${batch.productNo} ${batch.productName} 余 ${batch.quantity}`,
                      }))}
                    />
                  </Form.Item>
                  <Form.Item name="quantity" label="领用数量" rules={[{ required: true, message: '请填写数量' }]}>
                    <InputNumber min={1} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="reason" label="原因（可空）">
                    <Input />
                  </Form.Item>
                </>
              )}
              {modal.kind === 'production' && (
                <>
                  <Form.Item name="purpose" label="用途" rules={[{ required: true, message: '请选择用途' }]}>
                    <Select
                      options={[
                        { value: 'REWORK', label: `售后返工（退回核验返工 ${modal.item.reworkQuantity ?? 0}）` },
                        { value: 'REPLACEMENT', label: `售后补发生产（待补发 ${modal.item.pendingQuantity}）` },
                      ]}
                    />
                  </Form.Item>
                  <Form.Item name="node" label="工序" rules={[{ required: true, message: '请选择工序' }]}>
                    <Select
                      options={['MAKING', 'PACKING_BAG', 'SEAM_CUTTING'].map(node => ({
                        value: node,
                        label: PRODUCTION_NODE_LABELS[node],
                      }))}
                    />
                  </Form.Item>
                  <Form.Item name="planDate" label="计划日期" rules={[{ required: true, message: '请选择计划日期' }]}>
                    <DatePicker style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="employeeId" label="执行员工" rules={[{ required: true, message: '请选择执行员工' }]}>
                    <Select
                      showSearch
                      optionFilterProp="label"
                      options={employees.map(employee => ({
                        value: employee.id,
                        label: `${employee.employeeNo} ${employee.name}`,
                      }))}
                    />
                  </Form.Item>
                  <Form.Item name="quantity" label="计划数量" rules={[{ required: true, message: '请填写数量' }]}>
                    <InputNumber min={1} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="note" label="备注（可空）">
                    <Input />
                  </Form.Item>
                </>
              )}
              {modal.kind === 'replacement' && (
                <>
                  <Form.Item name="quantity" label="补发数量" rules={[{ required: true, message: '请填写数量' }]}>
                    <InputNumber min={1} max={modal.item.pendingQuantity} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                    提交后会创建补发草稿批次并立即确认；确认成功才增加售后已补发。
                  </Typography.Paragraph>
                </>
              )}
              {modal.kind === 'correct' && (
                <>
                  <Form.Item name="afterValue" label="更正后的退回数量" rules={[{ required: true, message: '请填写更正值' }]}>
                    <Input />
                  </Form.Item>
                  <Form.Item name="reason" label="更正原因" rules={[{ required: true, message: '请填写原因' }]}>
                    <Input />
                  </Form.Item>
                </>
              )}
            </Form>
          </>
        )}
      </Modal>
      <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
        草稿批次不提供售后入口；售后一律在订单上下文处理，不建立顶级售后工作区。
        {shipments.some(shipment => shipment.status === 'DRAFT') && '（当前存在草稿批次，确认后才能作为售后来源）'}
        {' '}已确认批次状态：{shipments.map(shipment => SHIPMENT_STATUS_LABELS[shipment.status]).join('、') || '无'}
      </Typography.Paragraph>
    </>
  );
}
