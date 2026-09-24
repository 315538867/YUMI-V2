import { useEffect, useState } from 'react';
import { App, Button, Card, DatePicker, Select, Space, Table, Tag, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import {
  listOrders,
  ORDER_STATUS_COLORS,
  ORDER_STATUS_LABELS,
  type OrderStatus,
  type OrderSummary,
} from '../../api/orders';
import { listCustomers, type CustomerView } from '../../api/catalog';
import { describeApiError } from '../../api/errors';

const { RangePicker } = DatePicker;

/**
 * 订单列表（任务 3.11）：主状态 / 生产进度 / 发货进度分列展示（派生状态来自服务端事实）。
 * 打开订单与新建/编辑草稿都是显式入口，列表本身只读。
 */
export function OrdersPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [rows, setRows] = useState<OrderSummary[]>([]);
  const [customers, setCustomers] = useState<CustomerView[]>([]);
  const [loading, setLoading] = useState(false);
  const [statusFilter, setStatusFilter] = useState<string | undefined>();
  const [customerFilter, setCustomerFilter] = useState<number | undefined>();
  const [range, setRange] = useState<[string, string] | null>(null);

  async function reload() {
    setLoading(true);
    try {
      setRows(
        await listOrders({
          status: statusFilter,
          customerId: customerFilter,
          orderDateFrom: range?.[0],
          orderDateTo: range?.[1],
        }),
      );
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void reload();
  }, []);

  useEffect(() => {
    listCustomers()
      .then(setCustomers)
      .catch(error => message.error(describeApiError(error)));
  }, []);

  return (
    <Card>
      <Typography.Title level={4} style={{ marginTop: 0 }}>
        订单
      </Typography.Title>
      <Space.Compact style={{ marginBottom: 12 }}>
        <Select
          placeholder="主状态"
          style={{ width: 130 }}
          allowClear
          value={statusFilter}
          onChange={value => setStatusFilter(value)}
          options={(Object.keys(ORDER_STATUS_LABELS) as OrderStatus[]).map(status => ({
            value: status,
            label: ORDER_STATUS_LABELS[status],
          }))}
        />
        <Select
          placeholder="客户"
          style={{ width: 200 }}
          allowClear
          showSearch
          optionFilterProp="label"
          value={customerFilter}
          onChange={value => setCustomerFilter(value)}
          options={customers.map(customer => ({ value: customer.id!, label: customer.name }))}
        />
        <RangePicker
          onChange={value =>
            setRange(value?.[0] && value?.[1] ? [value[0].format('YYYY-MM-DD'), value[1].format('YYYY-MM-DD')] : null)
          }
        />
        <Button onClick={() => void reload()}>查询</Button>
      </Space.Compact>
      <Button type="primary" style={{ marginLeft: 8 }} onClick={() => navigate('/orders/new')}>
        新建订单
      </Button>
      <Table<OrderSummary>
        style={{ marginTop: 12 }}
        size="small"
        rowKey="id"
        loading={loading}
        dataSource={rows}
        pagination={false}
        columns={[
          { title: '订单编号', dataIndex: 'orderNo', width: 110 },
          { title: '客户', dataIndex: 'customerName' },
          { title: '下单日期', dataIndex: 'orderDate', width: 120 },
          {
            title: '交期',
            dataIndex: 'expectedDeliveryDate',
            width: 120,
            render: value => value ?? '—',
          },
          { title: '应收', dataIndex: 'receivableAmount', width: 110, align: 'right' },
          {
            title: '主状态',
            dataIndex: 'status',
            width: 90,
            render: (status: OrderStatus) => (
              <Tag color={ORDER_STATUS_COLORS[status]}>{ORDER_STATUS_LABELS[status]}</Tag>
            ),
          },
          {
            title: '生产进度',
            key: 'production',
            width: 100,
            render: (_, row) => <Tag>{row.derived.production}</Tag>,
          },
          {
            title: '发货进度',
            key: 'shipment',
            width: 100,
            render: (_, row) => <Tag>{row.derived.shipment}</Tag>,
          },
          {
            title: '操作',
            key: 'action',
            width: 150,
            render: (_, row) => (
              <Space size={4}>
                <Button type="link" size="small" onClick={() => navigate(`/orders/${row.id}`)}>
                  打开
                </Button>
                {row.status === 'DRAFT' && (
                  <Button
                    type="link"
                    size="small"
                    onClick={() => navigate(`/orders/new?orderId=${row.id}`)}
                  >
                    编辑草稿
                  </Button>
                )}
              </Space>
            ),
          },
        ]}
      />
    </Card>
  );
}
