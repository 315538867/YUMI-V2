import { useEffect, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  DatePicker,
  Form,
  Input,
  InputNumber,
  Modal,
  Row,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import {
  adjustInventory,
  allocateInventory,
  cancelAllocation,
  createOpening,
  listAllocations,
  listBatches,
  listMovements,
  listSummary,
  MOVEMENT_TYPE_LABELS,
  NODE_LABELS,
  recommendBatches,
  reverseMovement,
  SEAM_STATE_LABELS,
  type AllocationView,
  type InventoryBatchView,
  type InventoryNode,
  type InventorySummaryView,
  type MovementView,
  type RecommendationView,
  type SeamState,
} from '../../api/inventory';
import { listProducts, type ProductSummary } from '../../api/catalog';
import { describeApiError } from '../../api/errors';

const NODE_OPTIONS = (Object.keys(NODE_LABELS) as InventoryNode[]).map(value => ({
  value,
  label: NODE_LABELS[value],
}));
const SEAM_OPTIONS = (Object.keys(SEAM_STATE_LABELS) as SeamState[]).map(value => ({
  value,
  label: SEAM_STATE_LABELS[value],
}));

/**
 * 库存全页工作区（任务 4.11）：汇总 / 批次 / 流水 / 订单领用四个只读页签，
 * 期初入库、盘点调整、订单领用、领用取消与流水冲销都从明确按钮进入独立操作状态。
 */
export function InventoryPage() {
  const { message } = App.useApp();
  const [tab, setTab] = useState('summary');
  const [products, setProducts] = useState<ProductSummary[]>([]);
  const [summary, setSummary] = useState<InventorySummaryView[]>([]);
  const [batches, setBatches] = useState<InventoryBatchView[]>([]);
  const [movements, setMovements] = useState<MovementView[]>([]);
  const [allocations, setAllocations] = useState<AllocationView[]>([]);
  const [includeEmpty, setIncludeEmpty] = useState(false);
  const [loading, setLoading] = useState(false);
  const [openingOpen, setOpeningOpen] = useState(false);
  const [adjustTarget, setAdjustTarget] = useState<InventoryBatchView | null>(null);
  const [reverseTarget, setReverseTarget] = useState<MovementView | null>(null);
  const [cancelTarget, setCancelTarget] = useState<AllocationView | null>(null);
  const [allocateOpen, setAllocateOpen] = useState(false);
  const [orderIdFilter, setOrderIdFilter] = useState<string>('');
  const [busy, setBusy] = useState(false);
  const [openingForm] = Form.useForm();
  const [adjustForm] = Form.useForm();
  const [reasonForm] = Form.useForm();
  const [allocateForm] = Form.useForm();
  const [recommendations, setRecommendations] = useState<RecommendationView[]>([]);

  async function reload() {
    setLoading(true);
    try {
      const [summaryRows, batchRows, movementRows] = await Promise.all([
        listSummary({ includeEmpty }),
        listBatches({ includeEmpty }),
        listMovements(),
      ]);
      setSummary(summaryRows);
      setBatches(batchRows);
      setMovements(movementRows);
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void reload();
  }, [includeEmpty]);

  useEffect(() => {
    listProducts({ status: 'ACTIVE' })
      .then(setProducts)
      .catch(error => message.error(describeApiError(error)));
  }, []);

  async function submitOpening() {
    const values = await openingForm.validateFields();
    setBusy(true);
    try {
      await createOpening({
        productId: values.productId,
        node: values.node,
        seamState: values.seamState,
        quantity: values.quantity,
        inventoryDate: values.inventoryDate.format('YYYY-MM-DD'),
        note: values.note,
      });
      message.success('期初库存已入库');
      setOpeningOpen(false);
      openingForm.resetFields();
      await reload();
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setBusy(false);
    }
  }

  async function submitAdjust() {
    const values = await adjustForm.validateFields();
    setBusy(true);
    try {
      const result = await adjustInventory({
        batchId: adjustTarget!.id,
        actualQuantity: values.actualQuantity,
        reason: values.reason,
        note: values.note,
      });
      message.success(result.quantity === adjustTarget!.quantity ? '数量一致，未生成盘点记录' : '盘点调整已记账');
      setAdjustTarget(null);
      adjustForm.resetFields();
      await reload();
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setBusy(false);
    }
  }

  async function submitReason(onDone: (reason: string) => Promise<void>) {
    const values = await reasonForm.validateFields();
    setBusy(true);
    try {
      await onDone(values.reason);
      reasonForm.resetFields();
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setBusy(false);
    }
  }

  async function loadRecommendations(productId: number, targetNode: InventoryNode) {
    try {
      setRecommendations(await recommendBatches(productId, targetNode));
    } catch (error) {
      message.error(describeApiError(error));
    }
  }

  async function submitAllocate() {
    const values = await allocateForm.validateFields();
    const lines = (values.lines ?? [])
      .filter((line: { batchId?: number; quantity?: number }) => line?.batchId && line?.quantity)
      .map((line: { batchId: number; quantity: number }) => ({
        batchId: line.batchId,
        orderItemId: values.orderItemId,
        quantity: line.quantity,
        targetNode: values.targetNode,
      }));
    if (lines.length === 0) {
      message.warning('至少选择一条批次与数量');
      return;
    }
    setBusy(true);
    try {
      await allocateInventory({ orderId: values.orderId, reason: values.reason, lines });
      message.success('库存领用已记账，并已接入订单履约');
      setAllocateOpen(false);
      allocateForm.resetFields();
      setRecommendations([]);
      await reload();
      if (orderIdFilter) {
        setAllocations(await listAllocations(Number(orderIdFilter)));
      }
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card
      loading={loading}
      title={
        <Space>
          <Typography.Text strong>库存</Typography.Text>
          <Typography.Text type="secondary">当前数量由不可变流水决定，发货不会再扣原库存</Typography.Text>
        </Space>
      }
      extra={
        <Space>
          <Button onClick={() => setOpeningOpen(true)}>期初入库</Button>
          <Button type="primary" onClick={() => setAllocateOpen(true)}>
            订单领用
          </Button>
          <Space size={4}>
            <Typography.Text type="secondary">显示零库存</Typography.Text>
            <Switch checked={includeEmpty} onChange={setIncludeEmpty} />
          </Space>
          <Button onClick={() => void reload()}>刷新</Button>
        </Space>
      }
    >
      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={[
          {
            key: 'summary',
            label: '汇总',
            children: (
              <Table<InventorySummaryView>
                size="small"
                rowKey={row => `${row.productId}-${row.node}-${row.seamState}`}
                dataSource={summary}
                pagination={false}
                locale={{ emptyText: '暂无库存；先录入期初库存' }}
                columns={[
                  { title: '商品', render: (_, row) => `${row.productNo} ${row.productName}` },
                  { title: '已完成工序', dataIndex: 'node', width: 120, render: value => NODE_LABELS[value as InventoryNode] },
                  { title: '缝边状态', dataIndex: 'seamState', width: 110, render: value => SEAM_STATE_LABELS[value as SeamState] },
                  { title: '当前数量', dataIndex: 'quantity', width: 100, align: 'right' },
                  { title: '批次数', dataIndex: 'batchCount', width: 90, align: 'right' },
                ]}
              />
            ),
          },
          {
            key: 'batches',
            label: '批次',
            children: (
              <Table<InventoryBatchView>
                size="small"
                rowKey="id"
                dataSource={batches}
                pagination={false}
                locale={{ emptyText: '暂无批次' }}
                columns={[
                  { title: '批次编号', dataIndex: 'batchNo', width: 110 },
                  { title: '商品', render: (_, row) => `${row.productNo} ${row.productName}` },
                  { title: '已完成工序', dataIndex: 'node', width: 120, render: value => NODE_LABELS[value as InventoryNode] },
                  { title: '缝边状态', dataIndex: 'seamState', width: 110, render: value => SEAM_STATE_LABELS[value as SeamState] },
                  { title: '当前数量', dataIndex: 'quantity', width: 100, align: 'right' },
                  { title: '来源', dataIndex: 'sourceType', width: 110 },
                  { title: '盘点日期', dataIndex: 'inventoryDate', width: 120 },
                  {
                    title: '操作',
                    key: 'action',
                    width: 90,
                    render: (_, row) => (
                      <Button type="link" size="small" onClick={() => setAdjustTarget(row)}>
                        盘点
                      </Button>
                    ),
                  },
                ]}
              />
            ),
          },
          {
            key: 'movements',
            label: '流水',
            children: (
              <Table<MovementView>
                size="small"
                rowKey="id"
                dataSource={movements}
                pagination={false}
                locale={{ emptyText: '暂无流水' }}
                expandable={{
                  expandedRowRender: movement => (
                    <Table
                      size="small"
                      rowKey="id"
                      pagination={false}
                      dataSource={movement.lines}
                      columns={[
                        { title: '批次', dataIndex: 'batchNo', width: 110 },
                        { title: '方向', dataIndex: 'direction', width: 70, render: value => <Tag>{value === 'IN' ? '入库' : '出库'}</Tag> },
                        { title: '数量', dataIndex: 'quantity', width: 80, align: 'right' },
                        { title: '出库前', dataIndex: 'quantityBefore', width: 90, align: 'right' },
                        { title: '出库后', dataIndex: 'quantityAfter', width: 90, align: 'right' },
                        { title: '工序', dataIndex: 'node', width: 110, render: value => NODE_LABELS[value as InventoryNode] },
                        { title: '商品', render: (_, line) => `${line.productNo ?? ''} ${line.productName ?? ''}` },
                        {
                          title: '来源业务',
                          key: 'source',
                          render: (_, line) => (line.orderNo ? `${line.orderNo} 第 ${line.orderLineNo} 行` : '—'),
                        },
                      ]}
                    />
                  ),
                }}
                columns={[
                  { title: '流水编号', dataIndex: 'movementNo', width: 110 },
                  { title: '业务类型', dataIndex: 'movementType', width: 120, render: value => MOVEMENT_TYPE_LABELS[value] ?? value },
                  { title: '业务日期', dataIndex: 'businessDate', width: 120 },
                  { title: '明细数', key: 'lines', width: 80, render: (_, row) => row.lines.length },
                  { title: '原因', dataIndex: 'reason', render: value => value || '—' },
                  { title: '操作人', dataIndex: 'operatorUsername', width: 110 },
                  {
                    title: '冲销关系',
                    key: 'reverse',
                    width: 130,
                    render: (_, row) => (row.reversesMovementId ? <Tag>冲销 #{row.reversesMovementId}</Tag> : '—'),
                  },
                  {
                    title: '操作',
                    key: 'action',
                    width: 90,
                    render: (_, row) =>
                      row.movementType === 'REVERSAL' ? (
                        '—'
                      ) : (
                        <Button type="link" size="small" danger onClick={() => setReverseTarget(row)}>
                          冲销
                        </Button>
                      ),
                  },
                ]}
              />
            ),
          },
          {
            key: 'allocations',
            label: '订单领用',
            children: (
              <>
                <Space.Compact style={{ marginBottom: 12 }}>
                  <Input
                    placeholder="订单 ID"
                    style={{ width: 140 }}
                    value={orderIdFilter}
                    onChange={event => setOrderIdFilter(event.target.value)}
                  />
                  <Button
                    onClick={() => {
                      if (!orderIdFilter) {
                        message.warning('请输入订单 ID');
                        return;
                      }
                      listAllocations(Number(orderIdFilter))
                        .then(setAllocations)
                        .catch(error => message.error(describeApiError(error)));
                    }}
                  >
                    查询领用
                  </Button>
                </Space.Compact>
                <Table<AllocationView>
                  size="small"
                  rowKey="id"
                  dataSource={allocations}
                  pagination={false}
                  locale={{ emptyText: '输入订单 ID 查询该订单的库存领用' }}
                  expandable={{
                    expandedRowRender: allocation => (
                      <Table
                        size="small"
                        rowKey="id"
                        pagination={false}
                        dataSource={allocation.lines}
                        columns={[
                          { title: '批次', dataIndex: 'batchNo', width: 110 },
                          { title: '订单明细', key: 'item', width: 130, render: (_, line) => `${line.orderNo ?? ''} 第 ${line.orderLineNo} 行` },
                          { title: '接入工序', dataIndex: 'targetNode', width: 110, render: value => NODE_LABELS[value as InventoryNode] },
                          { title: '数量', dataIndex: 'quantity', width: 80, align: 'right' },
                          { title: '出库前', dataIndex: 'quantityBefore', width: 90, align: 'right' },
                          { title: '出库后', dataIndex: 'quantityAfter', width: 90, align: 'right' },
                          { title: '履约事实', dataIndex: 'fulfillmentEntryId', width: 100 },
                        ]}
                      />
                    ),
                  }}
                  columns={[
                    { title: '领用单', dataIndex: 'id', width: 80 },
                    { title: '订单', dataIndex: 'orderNo', width: 110 },
                    {
                      title: '状态',
                      dataIndex: 'status',
                      width: 90,
                      render: value => <Tag>{value === 'CONFIRMED' ? '已领用' : '已取消'}</Tag>,
                    },
                    { title: '原因', dataIndex: 'reason', render: value => value || '—' },
                    { title: '取消原因', dataIndex: 'cancelReason', render: value => value || '—' },
                    {
                      title: '操作',
                      key: 'action',
                      width: 90,
                      render: (_, row) =>
                        row.status === 'CONFIRMED' ? (
                          <Button type="link" size="small" danger onClick={() => setCancelTarget(row)}>
                            取消
                          </Button>
                        ) : (
                          '—'
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
        open={openingOpen}
        title="期初入库"
        okText="入库"
        confirmLoading={busy}
        onOk={() => void submitOpening()}
        onCancel={() => setOpeningOpen(false)}
        destroyOnHidden
      >
        <Form form={openingForm} layout="vertical" initialValues={{ seamState: 'NONE', inventoryDate: dayjs() }}>
          <Form.Item name="productId" label="商品" rules={[{ required: true, message: '请选择商品' }]}>
            <Select
              showSearch
              optionFilterProp="label"
              options={products.map(product => ({ value: product.id, label: `${product.productNo} ${product.name}` }))}
            />
          </Form.Item>
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item name="node" label="已完成工序" rules={[{ required: true, message: '请选择工序' }]}>
                <Select options={NODE_OPTIONS} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="seamState" label="缝边状态" rules={[{ required: true }]}>
                <Select options={SEAM_OPTIONS} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="quantity" label="数量" rules={[{ required: true, message: '请输入数量' }]}>
                <InputNumber min={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="inventoryDate" label="盘点日期" rules={[{ required: true, message: '请选择日期' }]}>
            <DatePicker style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="note" label="备注">
            <Input placeholder="来源与说明（可空）" />
          </Form.Item>
        </Form>
        <Alert type="info" showIcon title="期初只记录本系统的库存事实，不伪造历史订单、生产或工资记录。" />
      </Modal>

      <Modal
        open={adjustTarget !== null}
        title={adjustTarget ? `盘点调整 · ${adjustTarget.batchNo}` : ''}
        okText="提交盘点"
        confirmLoading={busy}
        onOk={() => void submitAdjust()}
        onCancel={() => setAdjustTarget(null)}
        destroyOnHidden
      >
        <Form form={adjustForm} layout="vertical">
          <Form.Item label="账面数量">
            <Typography.Text>{adjustTarget?.quantity}</Typography.Text>
          </Form.Item>
          <Form.Item name="actualQuantity" label="实际数量" rules={[{ required: true, message: '请输入实际数量' }]}>
            <InputNumber min={0} precision={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="reason" label="盘点原因" rules={[{ required: true, whitespace: true, message: '请填写原因' }]}>
            <Input placeholder="如：复盘多出 / 破损" />
          </Form.Item>
          <Form.Item name="note" label="备注">
            <Input />
          </Form.Item>
        </Form>
        <Alert type="info" showIcon title="按差异生成盘盈或盘亏流水；数量一致时不生成记录，原流水不变。" />
      </Modal>

      <Modal
        open={allocateOpen}
        title="订单领用"
        okText="确认领用"
        width={720}
        confirmLoading={busy}
        onOk={() => void submitAllocate()}
        onCancel={() => setAllocateOpen(false)}
        destroyOnHidden
      >
        <Form form={allocateForm} layout="vertical">
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item name="orderId" label="订单 ID" rules={[{ required: true, message: '请输入订单 ID' }]}>
                <InputNumber min={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="orderItemId" label="订单明细 ID" rules={[{ required: true, message: '请输入明细 ID' }]}>
                <InputNumber min={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="productId" label="商品" rules={[{ required: true, message: '请选择商品' }]}>
                <Select
                  showSearch
                  optionFilterProp="label"
                  options={products.map(product => ({ value: product.id, label: `${product.productNo} ${product.name}` }))}
                  onChange={value => {
                    const target = allocateForm.getFieldValue('targetNode');
                    if (target) {
                      void loadRecommendations(value, target);
                    }
                  }}
                />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="targetNode" label="接入工序" rules={[{ required: true, message: '请选择接入工序' }]}>
                <Select
                  options={NODE_OPTIONS}
                  onChange={value => {
                    const product = allocateForm.getFieldValue('productId');
                    if (product) {
                      void loadRecommendations(product, value);
                    }
                  }}
                />
              </Form.Item>
            </Col>
            <Col span={16}>
              <Form.Item name="reason" label="领用原因">
                <Input placeholder="如：客户急需" />
              </Form.Item>
            </Col>
          </Row>
          <Form.List name="lines">
            {fields => (
              <>
                {fields.map((field, index) => (
                  <Space key={field.key} style={{ display: 'flex', marginBottom: 8 }}>
                    <Form.Item name={[field.name, 'batchId']} rules={[{ required: true, message: '选择批次' }]} style={{ marginBottom: 0 }}>
                      <Select
                        style={{ width: 320 }}
                        placeholder="选择推荐批次"
                        options={recommendations.map(rec => ({
                          value: rec.batchId,
                          label: `${rec.batchNo} · ${NODE_LABELS[rec.node]}/${SEAM_STATE_LABELS[rec.seamState]} · 可用 ${rec.quantity}`,
                        }))}
                      />
                    </Form.Item>
                    <Form.Item name={[field.name, 'quantity']} rules={[{ required: true, message: '数量' }]} style={{ marginBottom: 0 }}>
                      <InputNumber min={1} precision={0} placeholder="数量" />
                    </Form.Item>
                    <Button
                      type="link"
                      danger
                      onClick={() => {
                        const next = [...(allocateForm.getFieldValue('lines') ?? [])];
                        next.splice(index, 1);
                        allocateForm.setFieldsValue({ lines: next });
                      }}
                    >
                      移除
                    </Button>
                  </Space>
                ))}
                <Button
                  onClick={() =>
                    allocateForm.setFieldsValue({
                      lines: [...(allocateForm.getFieldValue('lines') ?? []), { batchId: undefined, quantity: undefined }],
                    })
                  }
                >
                  添加批次
                </Button>
              </>
            )}
          </Form.List>
        </Form>
        <Alert
          type="info"
          showIcon
          title="只列出与该接入工序兼容的批次（接入矩阵）；领用会扣减原批次并接入订单履约，发货不会再扣原库存。"
        />
      </Modal>

      <Modal
        open={reverseTarget !== null}
        title={reverseTarget ? `冲销流水 ${reverseTarget.movementNo}` : ''}
        okText="确认冲销"
        confirmLoading={busy}
        onOk={() =>
          void submitReason(async reason => {
            await reverseMovement(reverseTarget!.id, reason);
            message.success('已生成冲销流水，原流水保留');
            setReverseTarget(null);
            await reload();
          })
        }
        onCancel={() => setReverseTarget(null)}
        destroyOnHidden
      >
        <Form form={reasonForm} layout="vertical">
          <Form.Item name="reason" label="冲销原因" rules={[{ required: true, whitespace: true, message: '请填写原因' }]}>
            <Input placeholder="如：录入错误" />
          </Form.Item>
        </Form>
        <Alert type="warning" showIcon title="冲销只追加反向流水并保留原流水；已被后续业务消费的流水不能直接冲销。" />
      </Modal>

      <Modal
        open={cancelTarget !== null}
        title={cancelTarget ? `取消领用 #${cancelTarget.id}` : ''}
        okText="确认取消"
        confirmLoading={busy}
        onOk={() =>
          void submitReason(async reason => {
            await cancelAllocation(cancelTarget!.id, reason);
            message.success('领用已取消，库存已按反向流水回补');
            setCancelTarget(null);
            if (orderIdFilter) {
              setAllocations(await listAllocations(Number(orderIdFilter)));
            }
            await reload();
          })
        }
        onCancel={() => setCancelTarget(null)}
        destroyOnHidden
      >
        <Form form={reasonForm} layout="vertical">
          <Form.Item name="reason" label="取消原因" rules={[{ required: true, whitespace: true, message: '请填写原因' }]}>
            <Input placeholder="如：客户取消" />
          </Form.Item>
        </Form>
        <Alert type="warning" showIcon title="只有尚未进入后续生产、核验或发货的领用才能取消；取消生成反向库存与反向履约事实。" />
      </Modal>
    </Card>
  );
}
