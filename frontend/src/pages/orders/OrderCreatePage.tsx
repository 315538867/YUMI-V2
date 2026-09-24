import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  DatePicker,
  Divider,
  Form,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Steps,
  Table,
  Tag,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  confirmOrder,
  createOrder,
  getOrder,
  updateOrder,
  type OrderDetail,
  type OrderItemRequest,
  type OrderWriteRequest,
} from '../../api/orders';
import { listProducts, listCustomers, type CustomerView, type ProductSummary } from '../../api/catalog';
import { listStaticDataItems, type StaticDataItem } from '../../api/staticData';
import { describeApiError } from '../../api/errors';

interface HeaderValues {
  customerId?: number;
  orderDate?: dayjs.Dayjs;
  expectedDeliveryDate?: dayjs.Dayjs | null;
  recipientName?: string;
  recipientPhone?: string;
  region?: string;
  address?: string;
  note?: string;
}

interface DraftItem {
  key: string;
  productId?: number;
  quantity?: number;
  seamQuantity?: number;
  unitPrice?: string;
  seamTypeId?: number | null;
  seamFee?: string;
  note?: string;
}

const STEPS = ['客户与收货', '商品明细与 Q/E', '金额与优惠', '确认复核'];

/**
 * 订单步骤化全页工作区（任务 3.11）：客户/收货 → 商品明细与 Q/E → 金额与优惠 → 确认复核。
 * 金额一律取服务端保存后的返回值，页面不做任何客户端金额计算，也不允许覆盖服务端结果；
 * 编辑既有草稿通过 `/orders/new?orderId=` 进入同一工作区（显式入口，不新增路由）。
 */
export function OrderCreatePage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const editingId = params.get('orderId');
  const [form] = Form.useForm<HeaderValues>();
  // 表头值必须由页面持有：离开第 1 步后该 Form 会卸载，antd 会注销字段并丢掉取值
  const [header, setHeader] = useState<HeaderValues>({});
  const [discount, setDiscount] = useState<string | undefined>();
  const [step, setStep] = useState(0);
  const [customers, setCustomers] = useState<CustomerView[]>([]);
  const [products, setProducts] = useState<ProductSummary[]>([]);
  const [seamTypes, setSeamTypes] = useState<StaticDataItem[]>([]);
  const [items, setItems] = useState<DraftItem[]>([emptyItem()]);
  const [saved, setSaved] = useState<OrderDetail | null>(null);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(true);

  useEffect(() => {
    (async () => {
      try {
        const [customerList, productList, seamList] = await Promise.all([
          listCustomers(),
          listProducts({ status: 'ACTIVE' }),
          listStaticDataItems('SEAM_TYPE'),
        ]);
        setCustomers(customerList);
        setProducts(productList);
        setSeamTypes(seamList);
        if (editingId) {
          const order = await getOrder(editingId);
          const loaded: HeaderValues = {
            customerId: order.customerId,
            orderDate: dayjs(order.orderDate),
            expectedDeliveryDate: order.expectedDeliveryDate ? dayjs(order.expectedDeliveryDate) : null,
            recipientName: order.recipientName,
            recipientPhone: order.recipientPhone,
            region: order.region,
            address: order.address,
            note: order.note ?? undefined,
          };
          form.setFieldsValue(loaded);
          setHeader(loaded);
          setDiscount(order.discountAmount);
          setItems(
            order.items.map(item => ({
              key: String(item.id),
              productId: item.productId,
              quantity: item.quantity,
              seamQuantity: item.seamQuantity,
              unitPrice: item.unitPrice,
              seamTypeId: item.seamTypeId ?? null,
              seamFee: item.seamFee,
              note: item.note ?? undefined,
            })),
          );
          setSaved(order);
          setDirty(false);
        }
      } catch (error) {
        message.error(describeApiError(error));
      }
    })();
  }, [editingId]);

  const customer = useMemo(
    () => customers.find(entry => entry.id === header.customerId),
    [customers, header.customerId],
  );

  function emptyItem(): DraftItem {
    return { key: crypto.randomUUID(), quantity: 1, seamQuantity: 0 };
  }

  function patchItem(key: string, patch: Partial<DraftItem>) {
    setItems(previous => previous.map(item => (item.key === key ? { ...item, ...patch } : item)));
    setDirty(true);
  }

  function pickProduct(key: string, productId: number) {
    const product = products.find(entry => entry.id === productId);
    patchItem(key, {
      productId,
      // 成交价默认取商品销售单价（服务端未传时也取该值），单价是输入项而非派生金额
      unitPrice: product ? String(product.salePrice) : undefined,
    });
  }

  function buildItems(): OrderItemRequest[] | null {
    const requests: OrderItemRequest[] = [];
    for (const item of items) {
      if (!item.productId || !item.quantity || item.quantity < 1) {
        message.warning('每条明细都要选商品并填写大于 0 的数量');
        return null;
      }
      const seamQuantity = item.seamQuantity ?? 0;
      if (seamQuantity < 0 || seamQuantity > item.quantity) {
        message.warning('缝边数量必须在 0 与明细数量之间');
        return null;
      }
      requests.push({
        productId: item.productId,
        quantity: item.quantity,
        seamQuantity,
        unitPrice: item.unitPrice,
        seamTypeId: seamQuantity > 0 ? item.seamTypeId ?? null : null,
        seamFee: seamQuantity > 0 ? item.seamFee : undefined,
        note: item.note,
      });
    }
    return requests;
  }

  function buildBody(): OrderWriteRequest | null {
    const requests = buildItems();
    if (requests === null) {
      return null;
    }
    if (!header.customerId || !header.orderDate) {
      message.warning('请先选择客户与下单日期');
      return null;
    }
    return {
      customerId: header.customerId,
      orderDate: header.orderDate.format('YYYY-MM-DD'),
      expectedDeliveryDate: header.expectedDeliveryDate?.format('YYYY-MM-DD') ?? null,
      recipientName: header.recipientName,
      recipientPhone: header.recipientPhone,
      region: header.region,
      address: header.address,
      note: header.note,
      discountAmount: discount,
      items: requests,
    };
  }

  /** 保存草稿并展示服务端返回的权威金额；客户端不计算、不覆盖任何金额。 */
  async function save(): Promise<OrderDetail | null> {
    const body = buildBody();
    if (body === null) {
      return null;
    }
    setSaving(true);
    try {
      const result = saved
        ? await updateOrder(saved.id, { ...body, version: saved.version })
        : await createOrder(body);
      setSaved(result);
      setDirty(false);
      if (!editingId) {
        navigate(`/orders/new?orderId=${result.id}`, { replace: true });
      }
      return result;
    } catch (error) {
      message.error(describeApiError(error));
      return null;
    } finally {
      setSaving(false);
    }
  }

  async function goNext() {
    if (step === 0) {
      const values = await form.validateFields(['customerId', 'orderDate']);
      if (!values.customerId || !values.orderDate) {
        return;
      }
      setStep(1);
      return;
    }
    if (step === 1) {
      const result = await save();
      if (result) {
        setStep(2);
      }
      return;
    }
    if (step === 2) {
      const result = await save();
      if (result) {
        setStep(3);
      }
    }
  }

  async function submitConfirm() {
    const current = dirty || !saved ? await save() : saved;
    if (!current) {
      return;
    }
    setSaving(true);
    try {
      await confirmOrder(current.id);
      message.success('订单已确认，快照已冻结');
      navigate(`/orders/${current.id}`);
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setSaving(false);
    }
  }

  return (
    <Row gutter={24}>
      <Col span={17}>
        <Card>
          <Typography.Title level={4} style={{ marginTop: 0 }}>
            {editingId ? `编辑订单草稿 ${saved?.orderNo ?? ''}` : '新建订单'}
          </Typography.Title>
          <Steps current={step} size="small" items={STEPS.map(title => ({ title }))} style={{ marginBottom: 20 }} />

          {step === 0 && (
            <Form
              form={form}
              layout="vertical"
              initialValues={header}
              onValuesChange={(_, all) => {
                setHeader(previous => ({ ...previous, ...all }));
                setDirty(true);
              }}
            >
              <Row gutter={16}>
                <Col span={12}>
                  <Form.Item name="customerId" label="客户" rules={[{ required: true, message: '请选择客户' }]}>
                    <Select
                      showSearch
                      optionFilterProp="label"
                      placeholder="请选择客户"
                      options={customers.map(entry => ({ value: entry.id!, label: entry.name }))}
                      onChange={value => {
                        const picked = customers.find(entry => entry.id === value);
                        if (picked) {
                          form.setFieldsValue({
                            recipientName: picked.defaultRecipient,
                            recipientPhone: picked.defaultRecipientPhone,
                            region: picked.defaultRegion,
                            address: picked.defaultAddress,
                          });
                        }
                      }}
                    />
                  </Form.Item>
                </Col>
                <Col span={6}>
                  <Form.Item name="orderDate" label="下单日期" rules={[{ required: true, message: '请选择下单日期' }]}>
                    <DatePicker style={{ width: '100%' }} />
                  </Form.Item>
                </Col>
                <Col span={6}>
                  <Form.Item name="expectedDeliveryDate" label="期望交期（可空）">
                    <DatePicker style={{ width: '100%' }} />
                  </Form.Item>
                </Col>
                <Col span={6}>
                  <Form.Item name="recipientName" label="收货人" rules={[{ required: true, message: '请填写收货人' }]}>
                    <Input placeholder="默认取客户收货信息" />
                  </Form.Item>
                </Col>
                <Col span={6}>
                  <Form.Item name="recipientPhone" label="收货电话" rules={[{ required: true, message: '请填写收货电话' }]}>
                    <Input />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item name="region" label="地区" rules={[{ required: true, message: '请填写地区' }]}>
                    <Input />
                  </Form.Item>
                </Col>
                <Col span={24}>
                  <Form.Item name="address" label="详细地址" rules={[{ required: true, message: '请填写详细地址' }]}>
                    <Input />
                  </Form.Item>
                </Col>
                <Col span={24}>
                  <Form.Item name="note" label="整单备注（可空）">
                    <Input.TextArea rows={2} />
                  </Form.Item>
                </Col>
              </Row>
              {customer && (
                <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                  客户默认收货：{customer.defaultRecipient} {customer.defaultRecipientPhone} {customer.defaultRegion}{' '}
                  {customer.defaultAddress}
                </Typography.Paragraph>
              )}
            </Form>
          )}

          {step === 1 && (
            <>
              <Table<DraftItem>
                size="small"
                rowKey="key"
                dataSource={items}
                pagination={false}
                columns={[
                  {
                    title: '商品',
                    key: 'product',
                    width: 220,
                    render: (_, row) => (
                      <Select
                        showSearch
                        optionFilterProp="label"
                        style={{ width: '100%' }}
                        placeholder="选择商品"
                        value={row.productId}
                        onChange={value => pickProduct(row.key, value)}
                        options={products.map(product => ({
                          value: product.id,
                          label: `${product.productNo} ${product.name}`,
                        }))}
                      />
                    ),
                  },
                  {
                    title: '数量 Q',
                    key: 'quantity',
                    width: 100,
                    render: (_, row) => (
                      <InputNumber
                        min={1}
                        precision={0}
                        style={{ width: '100%' }}
                        value={row.quantity}
                        onChange={value => patchItem(row.key, { quantity: value ?? undefined })}
                      />
                    ),
                  },
                  {
                    title: '缝边数量 E',
                    key: 'seamQuantity',
                    width: 110,
                    render: (_, row) => (
                      <InputNumber
                        min={0}
                        max={row.quantity ?? 0}
                        precision={0}
                        style={{ width: '100%' }}
                        value={row.seamQuantity}
                        onChange={value => patchItem(row.key, { seamQuantity: value ?? 0 })}
                      />
                    ),
                  },
                  {
                    title: '缝边种类（E>0 必填）',
                    key: 'seamTypeId',
                    width: 180,
                    render: (_, row) => (
                      <Select
                        allowClear
                        style={{ width: '100%' }}
                        placeholder={row.seamQuantity ? '请选择缝边种类' : '默认不缝边剪袋'}
                        disabled={!row.seamQuantity}
                        value={row.seamTypeId ?? undefined}
                        onChange={value => patchItem(row.key, { seamTypeId: value ?? null })}
                        options={seamTypes.map(type => ({
                          value: type.id,
                          label: `${type.name} · ${type.costPrice} 元/件`,
                        }))}
                      />
                    ),
                  },
                  {
                    title: '成交价（元）',
                    key: 'unitPrice',
                    width: 120,
                    render: (_, row) => (
                      <Input
                        value={row.unitPrice}
                        onChange={event => patchItem(row.key, { unitPrice: event.target.value })}
                      />
                    ),
                  },
                  {
                    title: '缝边收费（元/件）',
                    key: 'seamFee',
                    width: 130,
                    render: (_, row) => (
                      <Input
                        disabled={!row.seamQuantity}
                        value={row.seamFee}
                        placeholder="默认取商品缝边价格"
                        onChange={event => patchItem(row.key, { seamFee: event.target.value })}
                      />
                    ),
                  },
                  {
                    title: '明细备注',
                    key: 'note',
                    render: (_, row) => (
                      <Input value={row.note} onChange={event => patchItem(row.key, { note: event.target.value })} />
                    ),
                  },
                  {
                    title: '操作',
                    key: 'action',
                    width: 70,
                    render: (_, row) => (
                      <Button
                        type="link"
                        size="small"
                        danger
                        disabled={items.length <= 1}
                        onClick={() => {
                          setItems(previous => previous.filter(item => item.key !== row.key));
                          setDirty(true);
                        }}
                      >
                        移除
                      </Button>
                    ),
                  },
                ]}
              />
              <Button
                style={{ marginTop: 12 }}
                onClick={() => {
                  setItems(previous => [...previous, emptyItem()]);
                  setDirty(true);
                }}
              >
                新增明细
              </Button>
              <Alert
                style={{ marginTop: 12 }}
                type="info"
                showIcon
                title="制作与捏毛装袋按数量 Q，缝边剪袋按 E，最终交付按 Q；三个工序不叠加计算。"
              />
            </>
          )}

          {step === 2 && (
            <Form layout="vertical">
              <Form.Item label="整单优惠（元，0 ≤ 优惠 ≤ 商品金额 + 缝边收费）">
                <Input
                  value={discount}
                  placeholder="如 10.0000，作用于整单应收"
                  onChange={event => {
                    setDiscount(event.target.value);
                    setDirty(true);
                  }}
                />
              </Form.Item>
              <Typography.Paragraph type="secondary">
                金额由服务端按明细与优惠计算：应收 = 商品金额 + 缝边收费 − 优惠；成本 = 商品成本 + 缝边成本；
                利润 = 应收 − 成本。进入下一步会保存草稿并展示服务端结果。
              </Typography.Paragraph>
            </Form>
          )}

          {step === 3 && (
            <>
              {saved ? (
                <>
                  <Typography.Paragraph>
                    订单编号 <b>{saved.orderNo}</b>，共 {saved.items.length} 条明细。以下金额全部来自服务端保存结果。
                  </Typography.Paragraph>
                  <Table<OrderDetail['items'][number]>
                    size="small"
                    rowKey="id"
                    dataSource={saved.items}
                    pagination={false}
                    columns={[
                      { title: '#', dataIndex: 'lineNo', width: 50 },
                      { title: '商品', render: (_, row) => `${row.productNo} ${row.productName}` },
                      { title: 'Q', dataIndex: 'quantity', width: 60 },
                      { title: 'E', dataIndex: 'seamQuantity', width: 60 },
                      { title: '商品金额', dataIndex: 'goodsAmount', width: 110, align: 'right' },
                      { title: '缝边收费', dataIndex: 'seamAmount', width: 110, align: 'right' },
                      { title: '商品成本', dataIndex: 'goodsCostAmount', width: 110, align: 'right' },
                      { title: '缝边成本', dataIndex: 'seamCostAmount', width: 110, align: 'right' },
                    ]}
                  />
                  <Divider />
                  <Space orientation="vertical" size={2}>
                    <span>应收合计：<b>{saved.receivableAmount}</b></span>
                    <span>成本合计：{saved.costAmount}（商品 {saved.goodsCostAmount} + 缝边 {saved.seamCostAmount}）</span>
                    <span>预计利润：<b>{saved.profitAmount}</b></span>
                  </Space>
                </>
              ) : (
                <Alert type="warning" showIcon title="尚未保存草稿，请先回到上一步保存后再复核" />
              )}
              {dirty && <Alert style={{ marginTop: 12 }} type="warning" showIcon title="有未保存的改动，确认前会先保存" />}
            </>
          )}

          <Space style={{ marginTop: 20 }}>
            <Button disabled={step === 0} onClick={() => setStep(previous => previous - 1)}>
              上一步
            </Button>
            {step < 3 ? (
              <Button type="primary" loading={saving} onClick={() => void goNext()}>
                {step === 1 || step === 2 ? '保存并继续' : '下一步'}
              </Button>
            ) : (
              <Button type="primary" loading={saving} onClick={() => void submitConfirm()}>
                确认订单
              </Button>
            )}
            <Button onClick={() => void save()} loading={saving}>
              保存草稿
            </Button>
            <Button onClick={() => navigate('/orders')}>返回列表</Button>
          </Space>
        </Card>
      </Col>
      <Col span={7}>
        <Card style={{ position: 'sticky', top: 16 }} title={<Space>金额汇总<Tag color="blue">服务端权威</Tag></Space>}>
          {saved ? (
            <table className="preview-table">
              <tbody>
                <tr><td>商品金额</td><td>{saved.goodsAmount}</td></tr>
                <tr><td>缝边收费</td><td>{saved.seamAmount}</td></tr>
                <tr><td>整单优惠</td><td>{saved.discountAmount}</td></tr>
                <tr><td>应收</td><td><b>{saved.receivableAmount}</b></td></tr>
                <tr><td>商品成本</td><td>{saved.goodsCostAmount}</td></tr>
                <tr><td>缝边成本</td><td>{saved.seamCostAmount}</td></tr>
                <tr><td>总成本</td><td><b>{saved.costAmount}</b></td></tr>
                <tr><td>预计利润</td><td><b>{saved.profitAmount}</b></td></tr>
              </tbody>
            </table>
          ) : (
            <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
              保存草稿后显示服务端计算的金额；页面不做客户端金额计算。
            </Typography.Paragraph>
          )}
          {dirty && saved && (
            <Typography.Paragraph type="warning" style={{ marginTop: 8, marginBottom: 0 }}>
              当前有未保存改动，金额为上次保存结果。
            </Typography.Paragraph>
          )}
        </Card>
      </Col>
    </Row>
  );
}
