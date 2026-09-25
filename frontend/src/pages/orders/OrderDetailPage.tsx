import { useEffect, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  Descriptions,
  Divider,
  Form,
  Input,
  Modal,
  Row,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import {
  cancelOrder,
  confirmOrder,
  createChangeOrder,
  getFulfillment,
  getOrder,
  listChangeOrders,
  ORDER_STATUS_COLORS,
  ORDER_STATUS_LABELS,
  type ChangeView,
  type FulfillmentView,
  type OrderDetail,
} from '../../api/orders';
import { describeApiError } from '../../api/errors';
import { printCurrentPage } from '../../lib/print';
import { ShipmentPanel } from './ShipmentPanel';
import { SettlementPanel } from './SettlementPanel';
import { AfterSalesPanel } from './AfterSalesPanel';

/**
 * 订单详情（任务 3.12）：订单内一组只读多 Tab（总览 / 商品与履约 / 发货与售后 / 资金与利润 / 订单资料与变更），
 * Tab 切换不新增路由、不预置表单；查看是只读的，新建/编辑/确认/处理都由显式入口进入独立操作状态。
 * 常规 Ant Design 后台管理布局：全页白底、浅色侧栏由外壳提供，这里只用现成组件与主题 token 表达层级。
 */
export function OrderDetailPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const { id } = useParams();
  const [order, setOrder] = useState<OrderDetail | null>(null);
  const [fulfillment, setFulfillment] = useState<FulfillmentView | null>(null);
  const [changes, setChanges] = useState<ChangeView[]>([]);
  const [loading, setLoading] = useState(true);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);

  async function reload() {
    if (!id) {
      return;
    }
    setLoading(true);
    try {
      const [detail, view, changeList] = await Promise.all([
        getOrder(id),
        getFulfillment(id),
        listChangeOrders(id),
      ]);
      setOrder(detail);
      setFulfillment(view);
      setChanges(changeList);
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void reload();
  }, [id]);

  async function onConfirm() {
    if (!order) {
      return;
    }
    setBusy(true);
    try {
      await confirmOrder(order.id);
      message.success('订单已确认');
      await reload();
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setBusy(false);
    }
  }

  async function onCancel() {
    if (!order) {
      return;
    }
    if (!reason.trim()) {
      message.warning('请填写取消原因');
      return;
    }
    setBusy(true);
    try {
      await cancelOrder(order.id, reason.trim());
      message.success('订单已取消');
      setCancelOpen(false);
      setReason('');
      await reload();
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setBusy(false);
    }
  }

  /** 发起变更：先创建变更草稿，再进入独立的变更确认页（详情页不内嵌表单）。 */
  async function onCreateChange() {
    if (!order) {
      return;
    }
    setBusy(true);
    try {
      const existing = changes.find(change => change.status === 'DRAFT');
      const change = existing ?? (await createChangeOrder(order.id, { reason: '' }));
      navigate(`/orders/${order.id}/changes/${change.id}`);
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setBusy(false);
    }
  }

  if (!order) {
    return <Card loading={loading} />;
  }

  const isDraft = order.status === 'DRAFT';
  const isConfirmed = order.status === 'CONFIRMED';

  const overview = (
    <>
      <Row gutter={16} style={{ marginBottom: 12 }}>
        <Col span={14}>
          <Descriptions size="small" column={2} bordered items={[
            { key: 'customer', label: '客户', children: order.customerName },
            { key: 'orderDate', label: '下单日期', children: order.orderDate },
            { key: 'delivery', label: '期望交期', children: order.expectedDeliveryDate ?? '—' },
            { key: 'status', label: '主状态', children: <Tag color={ORDER_STATUS_COLORS[order.status]}>{ORDER_STATUS_LABELS[order.status]}</Tag> },
          ]} />
        </Col>
        <Col span={10}>
          <Descriptions size="small" column={1} bordered items={[
            { key: 'scheduling', label: '排产状态', children: order.derived.scheduling },
            { key: 'production', label: '生产进度', children: order.derived.production },
            { key: 'demand', label: '需求处理', children: order.derived.demandHandling },
            { key: 'shipment', label: '发货进度', children: order.derived.shipment },
          ]} />
        </Col>
      </Row>
      <Table
        size="small"
        rowKey="orderItemId"
        pagination={false}
        dataSource={fulfillment?.items ?? []}
        columns={[
          { title: '#', dataIndex: 'lineNo', width: 50 },
          { title: '商品', render: (_, row) => `${row.productNo} ${row.productName}` },
          { title: '订购 Q', dataIndex: 'quantity', width: 80 },
          { title: '缝边 E', dataIndex: 'seamQuantity', width: 80 },
          { title: '已发', dataIndex: 'shipped', width: 70 },
          { title: '剩余需求', dataIndex: 'undelivered', width: 90 },
          { title: '商品金额', key: 'goods', width: 110, align: 'right', render: (_, row) => order.items.find(item => item.id === row.orderItemId)?.goodsAmount ?? '—' },
          { title: '缝边收费', key: 'seam', width: 110, align: 'right', render: (_, row) => order.items.find(item => item.id === row.orderItemId)?.seamAmount ?? '—' },
        ]}
      />
      <Divider />
      <Space orientation="vertical" size={2}>
        <span>商品金额 {order.goodsAmount} + 缝边收费 {order.seamAmount} − 优惠 {order.discountAmount} = 应收 <b>{order.receivableAmount}</b></span>
        <span>商品成本 {order.goodsCostAmount} + 缝边成本 {order.seamCostAmount} = 总成本 {order.costAmount}</span>
        <span>预计利润 <b>{order.profitAmount}</b></span>
      </Space>
    </>
  );

  const fulfillmentTab = (
    <Row gutter={[16, 16]}>
      {(fulfillment?.items ?? []).map(item => (
        <Col span={12} key={item.orderItemId}>
          <Card size="small" title={`#${item.lineNo} ${item.productNo} ${item.productName}`}>
            <Descriptions size="small" column={2} items={[
              { key: 'making', label: '制作（共同需求 Q）', children: `${item.makingRequired} / 已流入 ${item.makingInflow}` },
              { key: 'packing', label: '捏毛装袋（共同需求 Q）', children: `${item.packingRequired} / 已流入 ${item.packingInflow}` },
              { key: 'seam', label: '缝边剪袋（需求 E）', children: `${item.seamRequired} / 已流入 ${item.seamInflow}` },
              { key: 'final', label: '最终交付需求', children: item.finalRequired },
              { key: 'shippable', label: '当前可发货', children: item.shippable },
              { key: 'shipped', label: '累计有效发货', children: item.shipped },
              { key: 'surplus', label: '成品余量', children: item.finishedSurplus },
              { key: 'status', label: '生产进度', children: <Tag>{item.derived.production}</Tag> },
            ]} />
            <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
              不缝边需求 {item.noSeamRequired}、缝边剪袋需求 {item.seamRequired}；制作/捏毛装袋/最终需求都是 {item.quantity}，
              不按工序相加。
            </Typography.Paragraph>
          </Card>
        </Col>
      ))}
    </Row>
  );

  const tabs = [
    { key: 'overview', label: '总览', children: overview },
    { key: 'fulfillment', label: '商品与履约', children: fulfillmentTab },
    {
      key: 'shipment',
      label: '发货与售后',
      children: (
        <>
          <ShipmentPanel order={order} />
          <Divider />
          <AfterSalesPanel order={order} />
        </>
      ),
    },
    {
      key: 'finance',
      label: '资金与利润',
      children: <SettlementPanel order={order} />,
    },
    {
      key: 'records',
      label: '订单资料与变更',
      children: (
        <>
          <Descriptions size="small" column={2} bordered items={[
            { key: 'recipient', label: '收货人', children: `${order.recipientName} ${order.recipientPhone}` },
            { key: 'region', label: '地区', children: order.region },
            { key: 'address', label: '详细地址', children: order.address, span: 2 },
            { key: 'note', label: '整单备注', children: order.note || '—', span: 2 },
          ]} />
          <Divider />
          <Space style={{ marginBottom: 8 }}>
            <Typography.Text strong>变更单</Typography.Text>
            {isConfirmed && (
              <Button size="small" type="primary" loading={busy} onClick={() => void onCreateChange()}>
                发起变更
              </Button>
            )}
          </Space>
          <Table
            size="small"
            rowKey="id"
            pagination={false}
            dataSource={changes}
            locale={{ emptyText: '暂无变更单' }}
            columns={[
              { title: '变更单号', dataIndex: 'changeNo', width: 110 },
              { title: '状态', dataIndex: 'status', width: 90, render: value => <Tag>{value === 'DRAFT' ? '草稿' : '已确认'}</Tag> },
              { title: '原因', dataIndex: 'reason', render: value => value || '—' },
              {
                title: '操作',
                key: 'action',
                width: 100,
                render: (_, row) => (
                  <Button type="link" size="small" onClick={() => navigate(`/orders/${order.id}/changes/${row.id}`)}>
                    {row.status === 'DRAFT' ? '继续处理' : '查看'}
                  </Button>
                ),
              },
            ]}
          />
        </>
      ),
    },
  ];

  return (
    <Card
      loading={loading}
      title={
        <Space wrap>
          <Typography.Text strong>订单 {order.orderNo}</Typography.Text>
          <Tag color={ORDER_STATUS_COLORS[order.status]}>{ORDER_STATUS_LABELS[order.status]}</Tag>
          <Tag>{order.derived.production}</Tag>
          <Tag>{order.derived.shipment}</Tag>
        </Space>
      }
      extra={
        <Space>
          {isDraft && (
            <>
              <Button onClick={() => navigate(`/orders/new?orderId=${order.id}`)}>编辑草稿</Button>
              <Button type="primary" loading={busy} onClick={() => void onConfirm()}>
                确认订单
              </Button>
            </>
          )}
          {isConfirmed && (
            <Button type="primary" loading={busy} onClick={() => void onCreateChange()}>
              发起变更
            </Button>
          )}
          {(isDraft || isConfirmed) && <Button danger onClick={() => setCancelOpen(true)}>取消订单</Button>}
          <Button onClick={() => void printCurrentPage()}>打印 / PDF</Button>
          <Button onClick={() => navigate('/orders')}>返回列表</Button>
        </Space>
      }
    >
      <Tabs items={tabs} />
      <Modal
        open={cancelOpen}
        title="取消订单"
        okText="确认取消"
        confirmLoading={busy}
        onOk={() => void onCancel()}
        onCancel={() => setCancelOpen(false)}
        destroyOnHidden
      >
        <Form layout="vertical">
          <Form.Item label="取消原因（必填）" required>
            <Input.TextArea rows={3} value={reason} onChange={event => setReason(event.target.value)} />
          </Form.Item>
        </Form>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          草稿可直接取消；已确认订单只有在没有任何领用/生产/发货等执行事实时才能直接取消，
          否则请改用订单变更处理剩余需求与超出数量。取消不删除任何历史事实。
        </Typography.Paragraph>
      </Modal>
    </Card>
  );
}
