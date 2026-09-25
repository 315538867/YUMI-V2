import { useEffect, useState } from 'react';
import {
  Alert,
  App,
  Button,
  DatePicker,
  Descriptions,
  Divider,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import {
  closeOrder,
  getSettlement,
  RECEIVABLE_STATUS_COLORS,
  REFUND_SOURCE_LABELS,
  registerPayment,
  registerRefund,
  type RefundView,
  type SettlementView,
} from '../../api/settlement';
import type { OrderDetail } from '../../api/orders';
import { describeApiError, YumiApiError } from '../../api/errors';

type ModalState = { kind: 'payment' } | { kind: 'refund' } | null;

/**
 * 订单详情「资金与利润」Tab（任务 7.7）：金额快照与利润拆解 + 逐笔收退款 + 结清口径 + 关闭条件逐项。
 * 收退款只读展示（不可编辑删除）；登记收款/退款与关闭订单都从显式按钮进入操作。
 */
export function SettlementPanel({ order }: { order: OrderDetail }) {
  const { message } = App.useApp();
  const [form] = Form.useForm();
  const [settlement, setSettlement] = useState<SettlementView | null>(null);
  const [loading, setLoading] = useState(false);
  const [modal, setModal] = useState<ModalState>(null);
  const [submitting, setSubmitting] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<{ field: string; message: string }[]>([]);

  const isConfirmed = order.status === 'CONFIRMED';

  async function reload() {
    setLoading(true);
    try {
      setSettlement(await getSettlement(order.id));
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void reload();
  }, [order.id, order.version]);

  function open(kind: 'payment' | 'refund') {
    form.resetFields();
    setFieldErrors([]);
    form.setFieldsValue({ businessDate: dayjs(), method: '微信' });
    setModal({ kind });
  }

  async function submitModal() {
    if (!modal) {
      return;
    }
    const values = await form.validateFields();
    setSubmitting(true);
    setFieldErrors([]);
    try {
      if (modal.kind === 'payment') {
        await registerPayment(order.id, {
          amount: values.amount,
          businessDate: values.businessDate.format('YYYY-MM-DD'),
          method: values.method,
          note: values.note,
        });
      } else {
        await registerRefund(order.id, {
          amount: values.amount,
          businessDate: values.businessDate.format('YYYY-MM-DD'),
          method: values.method,
          reason: values.reason,
          note: values.note,
          sourceType: values.sourceType,
          sourceId: values.sourceId,
        });
      }
      message.success('已登记不可变事实');
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

  async function doClose() {
    Modal.confirm({
      title: '关闭订单',
      content:
        '关闭会在同一事务内重验「有效需求全部有效发货」「当前有效应收已结清」「无待退款」；关闭后不可重开，也不再允许新增生产、发货与收款。',
      okText: '确认关闭',
      cancelText: '取消',
      onOk: async () => {
        setFieldErrors([]);
        try {
          await closeOrder(order.id);
          message.success('订单已关闭');
          await reload();
        } catch (error) {
          if (error instanceof YumiApiError) {
            setFieldErrors(error.fieldErrors);
          }
          message.error(describeApiError(error));
        }
      },
    });
  }

  return (
    <>
      <Descriptions size="small" column={2} bordered items={[
        { key: 'goods', label: '商品金额', children: order.goodsAmount },
        { key: 'seam', label: '缝边收费', children: order.seamAmount },
        { key: 'discount', label: '整单优惠', children: order.discountAmount },
        { key: 'receivable', label: '当前有效应收', children: <b>{order.receivableAmount}</b> },
        { key: 'goodsCost', label: '商品成本', children: order.goodsCostAmount },
        { key: 'seamCost', label: '缝边成本', children: order.seamCostAmount },
        { key: 'cost', label: '总成本', children: order.costAmount },
        { key: 'profit', label: '预计利润', children: <b>{order.profitAmount}</b> },
      ]} />
      <Divider />
      <Space style={{ marginBottom: 8 }} wrap>
        <Typography.Text strong>收退款与结清</Typography.Text>
        {settlement && (
          <Tag color={RECEIVABLE_STATUS_COLORS[settlement.receivableStatus] ?? 'default'}>
            {settlement.receivableStatus}
          </Tag>
        )}
        <Button size="small" type="primary" disabled={!isConfirmed} onClick={() => open('payment')}>
          登记收款
        </Button>
        <Button size="small" disabled={order.status === 'DRAFT'} onClick={() => open('refund')}>
          登记退款
        </Button>
        <Button size="small" disabled={!isConfirmed} onClick={() => void doClose()}>
          关闭订单
        </Button>
        <Button size="small" onClick={() => void reload()} loading={loading}>
          刷新
        </Button>
      </Space>
      {settlement ? (
        <>
          <table className="preview-table">
            <tbody>
              <tr><td>累计订单收款</td><td>{settlement.paidAmount}</td></tr>
              <tr><td>累计订单变更退款</td><td>{settlement.changeRefundAmount}</td></tr>
              <tr><td>订单结清净额</td><td><b>{settlement.netSettledAmount}</b></td></tr>
              <tr><td>订单待退款</td><td>{settlement.refundPendingAmount}</td></tr>
              <tr><td>售后退款（单列）</td><td>{settlement.afterSalesRefundAmount}</td></tr>
              <tr><td>累计实际净收</td><td><b>{settlement.actualNetReceived}</b></td></tr>
            </tbody>
          </table>
          <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
            结清净额 = 累计订单收款 − 累计订单变更退款；待退款 = max(累计收款 − 当前有效应收 − 累计变更退款, 0)；
            售后退款单列于累计实际净收，不冲减结清净额、不产生新的原订单待收/待退。
          </Typography.Paragraph>

          <Typography.Text strong>收款</Typography.Text>
          <Table
            size="small"
            rowKey="id"
            style={{ margin: '8px 0' }}
            pagination={false}
            loading={loading}
            dataSource={settlement.payments}
            locale={{ emptyText: '尚未收款' }}
            columns={[
              { title: '收款编号', dataIndex: 'paymentNo', width: 110 },
              { title: '金额', dataIndex: 'amount', width: 110, align: 'right' },
              { title: '业务日期', dataIndex: 'businessDate', width: 110 },
              { title: '方式', dataIndex: 'method', width: 90 },
              { title: '备注', dataIndex: 'note', render: value => value || '—' },
              { title: '操作人', dataIndex: 'operatorUsername', width: 110 },
            ]}
          />
          <Typography.Text strong>退款</Typography.Text>
          <Table<RefundView>
            size="small"
            rowKey="id"
            style={{ marginTop: 8 }}
            pagination={false}
            loading={loading}
            dataSource={settlement.refunds}
            locale={{ emptyText: '尚未退款' }}
            columns={[
              { title: '退款编号', dataIndex: 'refundNo', width: 110 },
              { title: '金额', dataIndex: 'amount', width: 110, align: 'right' },
              { title: '业务日期', dataIndex: 'businessDate', width: 110 },
              { title: '方式', dataIndex: 'method', width: 90 },
              {
                title: '来源',
                key: 'source',
                width: 140,
                render: (_, row) => `${REFUND_SOURCE_LABELS[row.sourceType] ?? row.sourceType} #${row.sourceId}`,
              },
              { title: '原因', dataIndex: 'reason' },
              { title: '操作人', dataIndex: 'operatorUsername', width: 110 },
            ]}
          />

          <Divider />
          <Typography.Text strong>关闭条件（事务内逐项重验）</Typography.Text>
          <Table
            size="small"
            rowKey="name"
            style={{ marginTop: 8 }}
            pagination={false}
            dataSource={settlement.closeConditions}
            columns={[
              {
                title: '条件',
                dataIndex: 'name',
                render: (value: string) => <Typography.Text strong>{value}</Typography.Text>,
              },
              {
                title: '是否满足',
                dataIndex: 'satisfied',
                width: 100,
                render: (value: boolean) => (value ? <Tag color="green">满足</Tag> : <Tag color="red">未满足</Tag>),
              },
              { title: '当前情况', dataIndex: 'detail' },
            ]}
          />
        </>
      ) : (
        <Typography.Paragraph type="secondary">加载中…</Typography.Paragraph>
      )}

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
        title={modal?.kind === 'refund' ? '登记退款' : '登记收款'}
        onCancel={() => setModal(null)}
        onOk={() => void submitModal()}
        confirmLoading={submitting}
        okText="登记"
      >
        {modal && (
          <>
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 12 }}
              title={
                modal.kind === 'payment'
                  ? '收款是不可变事实：登记后不可修改或删除；草稿订单不允许收款。'
                  : '退款是不可变事实且必须关联来源（订单变更单或售后单）；累计退款不得超过累计收款。'
              }
            />
            <Form form={form} layout="vertical">
              <Form.Item name="amount" label="金额" rules={[{ required: true, message: '请填写金额' }]}>
                <Input placeholder="如 200.0000" />
              </Form.Item>
              <Form.Item name="businessDate" label="业务日期" rules={[{ required: true, message: '请选择日期' }]}>
                <DatePicker style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="method" label="方式" rules={[{ required: true, message: '请填写方式' }]}>
                <Input placeholder="现金 / 微信 / 银行转账" />
              </Form.Item>
              {modal.kind === 'refund' && (
                <>
                  <Form.Item name="sourceType" label="退款来源" rules={[{ required: true, message: '请选择来源' }]}>
                    <Select
                      options={[
                        { value: 'ORDER_CHANGE', label: '订单变更单（减单待退款）' },
                        { value: 'AFTER_SALES', label: '售后单（售后退款）' },
                      ]}
                    />
                  </Form.Item>
                  <Form.Item
                    name="sourceId"
                    label="来源记录 ID"
                    extra="变更单 ID 见订单资料与变更 Tab；售后单 ID 见发货与售后 Tab 的售后明细"
                    rules={[{ required: true, message: '请填写来源记录 ID' }]}
                  >
                    <InputNumber min={1} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="reason" label="退款原因" rules={[{ required: true, message: '请填写原因' }]}>
                    <Input />
                  </Form.Item>
                </>
              )}
              <Form.Item name="note" label="备注（可空）">
                <Input />
              </Form.Item>
            </Form>
          </>
        )}
      </Modal>
    </>
  );
}
