import { useEffect, useState } from 'react';
import {
  Alert,
  App,
  Button,
  DatePicker,
  Divider,
  Form,
  Input,
  InputNumber,
  Modal,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import {
  changeShipmentLogistics,
  confirmShipment,
  correctShipment,
  createShipmentDraft,
  listShipments,
  SHIPMENT_STATUS_COLORS,
  SHIPMENT_STATUS_LABELS,
  SOURCE_TYPE_LABELS,
  updateShipmentDraft,
  voidShipment,
  type ShipmentView,
} from '../../api/shipments';
import type { OrderDetail } from '../../api/orders';
import { printCurrentPage } from '../../lib/print';
import { describeApiError } from '../../api/errors';

/** 一个弹窗按 kind 决定标题、字段与提交动作（与生产工作台同构）。 */
type ModalState =
  | { kind: 'draft'; shipment?: ShipmentView }
  | { kind: 'logistics'; shipment: ShipmentView }
  | { kind: 'void'; shipment: ShipmentView }
  | { kind: 'correct'; shipment: ShipmentView }
  | null;

const TITLES: Record<Exclude<ModalState, null>['kind'], string> = {
  draft: '发货草稿',
  logistics: '物流修改',
  void: '作废发货批次',
  correct: '等量更正',
};

const HINTS: Record<Exclude<ModalState, null>['kind'], string> = {
  draft: '草稿不占用可发货、不增加累计发货、也不写任何事实；确认时才校验「本次 ≤ 当前可发货」与「累计 + 本次 ≤ 当前有效订购」。',
  logistics: '只能修改物流公司、单号、运费和备注，必须填写原因并保留修改前后值；数量、日期与来源关系不变。',
  void: '作废写反向事实恢复可发货与累计发货，保留原确认快照，不恢复原库存；已作废批次不可再次确认。',
  correct: '只有已关闭订单可以等量更正：同一事务内作废原批次并创建等量替代批次，交付数量不下降；一个原批次最多一次更正。',
};

/**
 * 订单详情「发货与售后」Tab 的发货区域（任务 6.8）：
 * 只读展示批次/明细/来源追溯/物流修改历史，**不预置表单**；
 * 新建、编辑、确认、物流修改、作废、更正都由显式按钮进入订单上下文的独立操作状态。
 */
export function ShipmentPanel({ order }: { order: OrderDetail }) {
  const { message } = App.useApp();
  const [form] = Form.useForm();
  const [shipments, setShipments] = useState<ShipmentView[]>([]);
  const [loading, setLoading] = useState(false);
  const [modal, setModal] = useState<ModalState>(null);
  const [submitting, setSubmitting] = useState(false);
  const [quantities, setQuantities] = useState<Record<number, number>>({});

  const isConfirmedOrder = order.status === 'CONFIRMED';

  async function reload() {
    setLoading(true);
    try {
      setShipments(await listShipments(order.id));
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void reload();
  }, [order.id, order.version]);

  function openDraft(shipment?: ShipmentView) {
    form.resetFields();
    const draftQuantities: Record<number, number> = {};
    for (const item of order.items) {
      const existing = shipment?.items.find(line => line.orderItemId === item.id);
      draftQuantities[item.id] = existing?.quantity ?? 0;
    }
    setQuantities(draftQuantities);
    form.setFieldsValue({
      shipmentDate: shipment ? dayjs(shipment.shipmentDate) : dayjs(),
      carrier: shipment?.carrier ?? undefined,
      trackingNo: shipment?.trackingNo ?? undefined,
      freight: shipment?.freight ?? '0.0000',
      logisticsNote: shipment?.logisticsNote ?? undefined,
      note: shipment?.note ?? undefined,
    });
    setModal({ kind: 'draft', shipment });
  }

  async function submitModal() {
    if (!modal) {
      return;
    }
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (modal.kind === 'draft') {
        const body = {
          shipmentDate: values.shipmentDate.format('YYYY-MM-DD'),
          carrier: values.carrier,
          trackingNo: values.trackingNo,
          freight: values.freight,
          logisticsNote: values.logisticsNote,
          note: values.note,
          items: order.items
            .map(item => ({ orderItemId: item.id, quantity: quantities[item.id] ?? 0 }))
            .filter(line => line.quantity > 0),
        };
        if (body.items.length === 0) {
          message.warning('至少一条发货明细，且数量大于 0');
          setSubmitting(false);
          return;
        }
        if (modal.shipment) {
          await updateShipmentDraft(order.id, modal.shipment.id, body);
        } else {
          await createShipmentDraft(order.id, body);
        }
      } else if (modal.kind === 'logistics') {
        await changeShipmentLogistics(order.id, modal.shipment.id, {
          carrier: values.carrier,
          trackingNo: values.trackingNo,
          freight: values.freight,
          logisticsNote: values.logisticsNote,
          reason: values.reason,
        });
      } else if (modal.kind === 'void') {
        await voidShipment(order.id, modal.shipment.id, values.reason);
      } else {
        await correctShipment(order.id, modal.shipment.id, values.reason);
      }
      message.success('操作已完成');
      setModal(null);
      await reload();
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setSubmitting(false);
    }
  }

  async function doConfirm(shipment: ShipmentView) {
    Modal.confirm({
      title: `确认发货批次 ${shipment.shipmentNo}`,
      content: '确认后冻结商品、客户、收货与数量快照，并消耗订单可发货；原库存不会再次扣减。',
      okText: '确认发货',
      cancelText: '取消',
      onOk: async () => {
        try {
          await confirmShipment(order.id, shipment.id);
          message.success('发货已确认');
          await reload();
        } catch (error) {
          message.error(describeApiError(error));
        }
      },
    });
  }

  return (
    <>
      <Space style={{ marginBottom: 8 }} wrap>
        <Typography.Text strong>发货批次</Typography.Text>
        <Button size="small" type="primary" disabled={!isConfirmedOrder} onClick={() => openDraft()}>
          新建发货草稿
        </Button>
        <Button size="small" onClick={() => void printCurrentPage()}>
          打印 / PDF
        </Button>
        <Button size="small" onClick={() => void reload()} loading={loading}>
          刷新
        </Button>
      </Space>
      {!isConfirmedOrder && (
        <Alert
          style={{ marginBottom: 8 }}
          type="info"
          showIcon
          title="只有已确认订单可以创建发货草稿；草稿可预览非正式清单，确认后才产生正式事实。"
        />
      )}
      <Table<ShipmentView>
        size="small"
        rowKey="id"
        loading={loading}
        dataSource={shipments}
        pagination={false}
        locale={{ emptyText: '暂无发货批次' }}
        expandable={{
          expandedRowRender: shipment => (
            <>
              <Table
                size="small"
                rowKey="id"
                pagination={false}
                dataSource={shipment.items}
                columns={[
                  { title: '明细', dataIndex: 'lineNo', width: 60, render: value => `#${value}` },
                  { title: '商品', render: (_, row) => `${row.productNo} ${row.productName}` },
                  { title: '本次数量', dataIndex: 'quantity', width: 90, align: 'right' },
                  {
                    title: '累计发货（快照）',
                    dataIndex: 'cumulativeShippedQuantity',
                    width: 130,
                    align: 'right',
                    render: value => value ?? '—',
                  },
                  {
                    title: '未交付（快照）',
                    dataIndex: 'undeliveredQuantity',
                    width: 120,
                    align: 'right',
                    render: value => value ?? '—',
                  },
                  {
                    title: '来源追溯',
                    key: 'sources',
                    render: (_, row) =>
                      row.sourceLinks.length === 0 ? (
                        '—'
                      ) : (
                        <Space size={4} wrap>
                          {row.sourceLinks.map(link => (
                            <Tag key={link.id}>
                              {SOURCE_TYPE_LABELS[link.sourceType] ?? link.sourceType} #{link.sourceId} × {link.quantity}
                            </Tag>
                          ))}
                        </Space>
                      ),
                  },
                  {
                    title: '收货快照',
                    key: 'recipient',
                    render: (_, row) => `${row.recipientName ?? ''} ${row.recipientPhone ?? ''} ${row.region ?? ''}`,
                  },
                ]}
              />
              <Divider style={{ margin: '12px 0' }} />
              <Typography.Text strong>物流修改历史</Typography.Text>
              <Table
                size="small"
                rowKey="id"
                style={{ marginTop: 8 }}
                pagination={false}
                dataSource={shipment.logisticsChanges}
                locale={{ emptyText: '未修改过物流' }}
                columns={[
                  { title: '物流公司', key: 'carrier', render: (_, row) => `${row.beforeCarrier ?? '—'} → ${row.afterCarrier ?? '—'}` },
                  { title: '单号', key: 'tracking', render: (_, row) => `${row.beforeTrackingNo ?? '—'} → ${row.afterTrackingNo ?? '—'}` },
                  { title: '运费', key: 'freight', render: (_, row) => `${row.beforeFreight ?? '—'} → ${row.afterFreight ?? '—'}` },
                  { title: '备注', key: 'note', render: (_, row) => `${row.beforeNote ?? '—'} → ${row.afterNote ?? '—'}` },
                  { title: '原因', dataIndex: 'reason' },
                ]}
              />
              <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
                来源追溯只说明本次发货消耗了哪些可发货来源；库存已在领用时扣减，发货不会再扣原库存。
              </Typography.Paragraph>
            </>
          ),
        }}
        columns={[
          {
            title: '批次编号',
            dataIndex: 'shipmentNo',
            width: 190,
            render: (value: string, row) => (
              <Space size={4}>
                {value}
                {row.afterSalesReplacement && <Tag color="purple">售后补发</Tag>}
              </Space>
            ),
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 100,
            render: (value: ShipmentView['status']) => (
              <Tag color={SHIPMENT_STATUS_COLORS[value]}>{SHIPMENT_STATUS_LABELS[value]}</Tag>
            ),
          },
          { title: '发货日期', dataIndex: 'shipmentDate', width: 110 },
          {
            title: '当前物流',
            key: 'current',
            render: (_, row) =>
              `${row.currentCarrier ?? '—'} ${row.currentTrackingNo ?? ''} 运费 ${row.currentFreight}`,
          },
          { title: '明细数', key: 'lines', width: 80, render: (_, row) => row.items.length },
          {
            title: '操作',
            key: 'action',
            width: 240,
            render: (_, row) => (
              <Space size={4} wrap>
                {row.status === 'DRAFT' && (
                  <>
                    <Button type="link" size="small" onClick={() => openDraft(row)}>
                      编辑草稿
                    </Button>
                    <Button type="link" size="small" onClick={() => void doConfirm(row)}>
                      确认
                    </Button>
                  </>
                )}
                {row.status === 'CONFIRMED' && (
                  <>
                    <Button
                      type="link"
                      size="small"
                      onClick={() => {
                        form.resetFields();
                        form.setFieldsValue({
                          carrier: row.currentCarrier ?? undefined,
                          trackingNo: row.currentTrackingNo ?? undefined,
                          freight: row.currentFreight,
                          logisticsNote: row.currentLogisticsNote ?? undefined,
                        });
                        setModal({ kind: 'logistics', shipment: row });
                      }}
                    >
                      物流修改
                    </Button>
                    <Button
                      type="link"
                      size="small"
                      onClick={() => {
                        form.resetFields();
                        setModal({ kind: 'void', shipment: row });
                      }}
                    >
                      作废
                    </Button>
                    <Button
                      type="link"
                      size="small"
                      onClick={() => {
                        form.resetFields();
                        setModal({ kind: 'correct', shipment: row });
                      }}
                    >
                      等量更正
                    </Button>
                  </>
                )}
                {row.status === 'VOIDED' && (
                  <Typography.Text type="secondary">{row.voidReason ?? '已作废'}</Typography.Text>
                )}
              </Space>
            ),
          },
        ]}
      />

      <Modal
        open={modal !== null}
        title={modal ? TITLES[modal.kind] : ''}
        onCancel={() => setModal(null)}
        onOk={() => void submitModal()}
        confirmLoading={submitting}
        okText="提交"
        width={720}
      >
        {modal && (
          <>
            <Alert type="info" showIcon style={{ marginBottom: 12 }} title={HINTS[modal.kind]} />
            <Form form={form} layout="vertical">
              {modal.kind === 'draft' && (
                <>
                  <Form.Item name="shipmentDate" label="发货日期" rules={[{ required: true, message: '请选择发货日期' }]}>
                    <DatePicker style={{ width: '100%' }} />
                  </Form.Item>
                  <Typography.Text strong>发货明细（草稿可改，确认后冻结）</Typography.Text>
                  <Table
                    size="small"
                    rowKey="id"
                    style={{ margin: '8px 0' }}
                    pagination={false}
                    dataSource={order.items}
                    columns={[
                      { title: '#', dataIndex: 'lineNo', width: 50 },
                      { title: '商品', render: (_, row) => `${row.productNo} ${row.productName}` },
                      { title: '订购 Q', dataIndex: 'quantity', width: 80, align: 'right' },
                      {
                        title: '本次发货数量',
                        key: 'shipQuantity',
                        width: 150,
                        render: (_, row) => (
                          <InputNumber
                            min={0}
                            max={row.quantity}
                            precision={0}
                            style={{ width: '100%' }}
                            value={quantities[row.id] ?? 0}
                            onChange={value => setQuantities(previous => ({ ...previous, [row.id]: value ?? 0 }))}
                          />
                        ),
                      },
                    ]}
                  />
                  <Form.Item name="carrier" label="物流公司（可空）">
                    <Input />
                  </Form.Item>
                  <Form.Item name="trackingNo" label="物流单号（可空）">
                    <Input />
                  </Form.Item>
                  <Form.Item name="freight" label="运费（可空）">
                    <Input placeholder="如 8.0000" />
                  </Form.Item>
                  <Form.Item name="logisticsNote" label="物流备注（可空）">
                    <Input />
                  </Form.Item>
                  <Form.Item name="note" label="批次备注（可空）">
                    <Input />
                  </Form.Item>
                </>
              )}
              {modal.kind === 'logistics' && (
                <>
                  <Form.Item name="carrier" label="物流公司">
                    <Input />
                  </Form.Item>
                  <Form.Item name="trackingNo" label="物流单号">
                    <Input />
                  </Form.Item>
                  <Form.Item name="freight" label="运费">
                    <Input />
                  </Form.Item>
                  <Form.Item name="logisticsNote" label="物流备注">
                    <Input />
                  </Form.Item>
                  <Form.Item name="reason" label="修改原因" rules={[{ required: true, message: '请填写修改原因' }]}>
                    <Input />
                  </Form.Item>
                </>
              )}
              {(modal.kind === 'void' || modal.kind === 'correct') && (
                <Form.Item
                  name="reason"
                  label={modal.kind === 'void' ? '作废原因' : '更正原因'}
                  rules={[{ required: true, message: '请填写原因' }]}
                >
                  <Input />
                </Form.Item>
              )}
            </Form>
          </>
        )}
      </Modal>
    </>
  );
}
