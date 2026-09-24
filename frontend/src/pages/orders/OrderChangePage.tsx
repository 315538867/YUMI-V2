import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  DatePicker,
  Descriptions,
  Divider,
  Input,
  InputNumber,
  Radio,
  Row,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import { useNavigate, useParams } from 'react-router-dom';
import {
  confirmChangeOrder,
  getChangeOrder,
  getFulfillment,
  getOrder,
  updateChangeOrder,
  type ChangeItemRequest,
  type ChangeView,
  type FulfillmentView,
  type OrderDetail,
} from '../../api/orders';
import { listProducts, type ProductSummary } from '../../api/catalog';
import { describeApiError } from '../../api/errors';

/** 变更操作页的本地明细行：以订单当前明细为基线，改值/移除，或新增行。 */
interface EditItem {
  key: string;
  orderItemId?: number | null;
  removed?: boolean;
  isNew?: boolean;
  productId?: number | null;
  quantity?: number | null;
  seamQuantity?: number | null;
  unitPrice?: string | null;
  note?: string | null;
  surplusDisposition?: string;
  surplusQuantity?: number;
  surplusReason?: string;
}

/** 表头变更：留空＝沿用原值，只提交管理员真正填写的字段。 */
interface HeaderDraft {
  expectedDeliveryDate?: dayjs.Dayjs | null;
  recipientName?: string;
  recipientPhone?: string;
  region?: string;
  address?: string;
  note?: string;
  discountAmount?: string;
}

/**
 * 订单变更确认操作页（任务 3.13）：在订单上下文里编辑变更明细与表头、逐项核对后确认。
 * 以订单当前明细为基线展示「变更前后」，只提交真正改动的行；逐项列出已发货下限、
 * 在制/合格超出处理、待退款影响与确认后不可覆盖的历史。确认后回到订单只读详情。
 */
export function OrderChangePage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const { id, changeId } = useParams();
  const [change, setChange] = useState<ChangeView | null>(null);
  const [order, setOrder] = useState<OrderDetail | null>(null);
  const [fulfillment, setFulfillment] = useState<FulfillmentView | null>(null);
  const [products, setProducts] = useState<ProductSummary[]>([]);
  const [items, setItems] = useState<EditItem[]>([]);
  const [headerDraft, setHeaderDraft] = useState<HeaderDraft>({});
  const [reason, setReason] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  async function reload() {
    if (!changeId || !id) {
      return;
    }
    setLoading(true);
    try {
      const [changeView, orderDetail, view, productList] = await Promise.all([
        getChangeOrder(changeId),
        getOrder(id),
        getFulfillment(id),
        listProducts({ status: 'ACTIVE' }),
      ]);
      setChange(changeView);
      setOrder(orderDetail);
      setFulfillment(view);
      setProducts(productList);
      setReason(changeView.reason ?? '');
      setHeaderDraft({
        expectedDeliveryDate: changeView.newExpectedDeliveryDate ? dayjs(changeView.newExpectedDeliveryDate) : null,
        recipientName: changeView.newRecipientName ?? undefined,
        recipientPhone: changeView.newRecipientPhone ?? undefined,
        region: changeView.newRegion ?? undefined,
        address: changeView.newAddress ?? undefined,
        note: changeView.newNote ?? undefined,
        discountAmount: changeView.newDiscountAmount ?? undefined,
      });
      // 草稿已有明细就用草稿；空草稿则以订单当前明细为基线，让管理员在其上改值/移除
      setItems(
        changeView.items.length > 0
          ? changeView.items.map(item => ({
              key: String(item.id),
              orderItemId: item.orderItemId ?? null,
              removed: item.changeType === 'REMOVE',
              isNew: item.changeType === 'ADD',
              productId: item.productId ?? null,
              quantity: item.afterQuantity,
              seamQuantity: item.afterSeamQuantity,
              unitPrice: item.afterUnitPrice ?? null,
              note: item.afterNote ?? null,
              surplusDisposition: item.surplusDisposition ?? undefined,
              surplusQuantity: item.surplusQuantity ?? undefined,
              surplusReason: item.surplusReason ?? undefined,
            }))
          : orderDetail.items.map(item => ({
              key: `line-${item.id}`,
              orderItemId: item.id,
              quantity: item.quantity,
              seamQuantity: item.seamQuantity,
              unitPrice: item.unitPrice,
              note: item.note ?? null,
            })),
      );
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void reload();
  }, [changeId, id]);

  const orderItems = order?.items ?? [];

  /** 逐行核对：目标数量、已发货下限、在制/合格与超出量，以及该行是否真的改了。 */
  const checks = useMemo(
    () =>
      items.map(item => {
        const current = orderItems.find(entry => entry.id === item.orderItemId);
        const balance = fulfillment?.items.find(entry => entry.orderItemId === item.orderItemId);
        const target = item.removed ? 0 : item.quantity ?? current?.quantity ?? 0;
        const inProcess = balance ? balance.makingInflow + balance.packingInflow + balance.seamInflow : 0;
        const shipped = balance?.shipped ?? 0;
        const surplus = item.isNew ? 0 : Math.max(0, inProcess - target);
        const changed = Boolean(
          item.isNew ||
            item.removed ||
            !current ||
            target !== current.quantity ||
            (item.seamQuantity ?? current.seamQuantity) !== current.seamQuantity ||
            (item.unitPrice ?? current.unitPrice) !== current.unitPrice ||
            (item.surplusDisposition ?? '') !== '' ||
            (item.note ?? '') !== (current.note ?? ''),
        );
        return { current, target, shipped, inProcess, surplus, changed, belowShipped: !item.isNew && target < shipped };
      }),
    [items, orderItems, fulfillment],
  );

  if (!change || !order) {
    return <Card loading={loading} />;
  }

  const confirmed = change.status === 'CONFIRMED';

  function patchItem(key: string, patch: Partial<EditItem>) {
    setItems(previous => previous.map(item => (item.key === key ? { ...item, ...patch } : item)));
  }

  /** 只提交真正改动的行：未改的行不写入变更明细，避免变更单被噪声占满。 */
  function buildItems(): ChangeItemRequest[] {
    return items
      .map((item, index) => ({ item, check: checks[index] }))
      .filter(({ item, check }) => check.changed || (item.surplusDisposition ?? '') !== '')
      .map(({ item }) => ({
        orderItemId: item.isNew ? null : item.orderItemId ?? null,
        remove: item.removed ?? false,
        productId: item.isNew ? item.productId ?? null : null,
        quantity: item.removed ? null : item.quantity ?? null,
        seamQuantity: item.removed ? null : item.seamQuantity ?? null,
        unitPrice: item.removed ? null : item.unitPrice ?? null,
        seamFee: null,
        note: item.removed ? null : item.note ?? null,
        surplusDisposition: item.surplusDisposition ?? null,
        surplusQuantity: item.surplusQuantity ?? null,
        surplusReason: item.surplusReason ?? null,
      }));
  }

  function buildBody() {
    return {
      reason,
      expectedDeliveryDate: headerDraft.expectedDeliveryDate?.format('YYYY-MM-DD') ?? null,
      recipientName: headerDraft.recipientName || undefined,
      recipientPhone: headerDraft.recipientPhone || undefined,
      region: headerDraft.region || undefined,
      address: headerDraft.address || undefined,
      note: headerDraft.note || undefined,
      discountAmount: headerDraft.discountAmount || undefined,
      items: buildItems(),
    };
  }

  async function save(): Promise<boolean> {
    if (items.some(item => item.isNew && !item.productId)) {
      message.warning('新增明细必须选择商品');
      return false;
    }
    setBusy(true);
    try {
      const updated = await updateChangeOrder(change!.id, buildBody());
      setChange(updated);
      return true;
    } catch (error) {
      message.error(describeApiError(error));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function onSave() {
    if (await save()) {
      message.success('变更草稿已保存');
    }
  }

  async function onConfirm() {
    const blocking = checks.filter(
      (check, index) => check.belowShipped || (check.surplus > 0 && !items[index].surplusDisposition),
    );
    if (blocking.length > 0) {
      message.warning('减单不得低于累计有效发货；超出在制/合格数量必须逐项选择处理方案');
      return;
    }
    if (!(await save())) {
      return;
    }
    setBusy(true);
    try {
      await confirmChangeOrder(change!.id);
      message.success('变更已确认，需求与金额已按变更更新');
      navigate(`/orders/${order!.id}`);
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
        <Space wrap>
          <Typography.Text strong>变更 {change.changeNo}</Typography.Text>
          <Tag>{confirmed ? '已确认' : '草稿'}</Tag>
          <Typography.Text type="secondary">订单 {order.orderNo}</Typography.Text>
        </Space>
      }
      extra={
        <Space>
          {!confirmed && (
            <>
              <Button loading={busy} onClick={() => void onSave()}>
                保存变更草稿
              </Button>
              <Button type="primary" loading={busy} onClick={() => void onConfirm()}>
                确认变更
              </Button>
            </>
          )}
          <Button onClick={() => navigate(`/orders/${order.id}`)}>返回订单</Button>
        </Space>
      }
    >
      {confirmed && (
        <Alert
          style={{ marginBottom: 12 }}
          type="success"
          showIcon
          title="该变更已确认，需求、金额与履约事实已生效且不可覆盖"
          description="变更单确认后只读；如需再次调整，请发起新的变更单。已确认的事实不通过物理删除或覆盖修正。"
        />
      )}

      <Descriptions size="small" column={3} bordered items={[
        { key: 'receivable', label: '当前应收（变更前）', children: order.receivableAmount },
        { key: 'goods', label: '当前商品金额', children: order.goodsAmount },
        { key: 'seam', label: '当前缝边收费', children: order.seamAmount },
      ]} />

      <Divider titlePlacement="start">明细变更前后</Divider>
      <Table<EditItem>
        size="small"
        rowKey="key"
        pagination={false}
        dataSource={items}
        locale={{ emptyText: '订单没有明细' }}
        columns={[
          {
            title: '变更类型',
            key: 'type',
            width: 100,
            render: (_, item) => {
              const check = checks[items.indexOf(item)];
              if (item.isNew) {
                return <Tag color="blue">新增</Tag>;
              }
              if (item.removed) {
                return <Tag color="red">移除</Tag>;
              }
              return check?.changed ? <Tag color="orange">已改</Tag> : <Tag>未变更</Tag>;
            },
          },
          {
            title: '商品',
            key: 'product',
            width: 210,
            render: (_, item) => {
              const current = orderItems.find(entry => entry.id === item.orderItemId);
              if (item.isNew) {
                return (
                  <Select
                    showSearch
                    optionFilterProp="label"
                    style={{ width: '100%' }}
                    placeholder="选择商品"
                    disabled={confirmed}
                    value={item.productId ?? undefined}
                    onChange={value => {
                      const product = products.find(entry => entry.id === value);
                      patchItem(item.key, {
                        productId: value,
                        unitPrice: product ? String(product.salePrice) : null,
                      });
                    }}
                    options={products.map(product => ({
                      value: product.id,
                      label: `${product.productNo} ${product.name}`,
                    }))}
                  />
                );
              }
              return current ? `#${current.lineNo} ${current.productNo} ${current.productName}` : '—';
            },
          },
          {
            title: '数量 Q（前 → 后）',
            key: 'quantity',
            width: 170,
            render: (_, item) => {
              const current = orderItems.find(entry => entry.id === item.orderItemId);
              return (
                <Space size={4}>
                  <Typography.Text type="secondary">{item.isNew ? '—' : current?.quantity ?? '—'}</Typography.Text>
                  <span>→</span>
                  <InputNumber
                    min={0}
                    precision={0}
                    disabled={confirmed || item.removed}
                    value={item.removed ? 0 : item.quantity ?? undefined}
                    onChange={value => patchItem(item.key, { quantity: value ?? null })}
                  />
                </Space>
              );
            },
          },
          {
            title: '缝边数量 E（前 → 后）',
            key: 'seamQuantity',
            width: 170,
            render: (_, item) => {
              const current = orderItems.find(entry => entry.id === item.orderItemId);
              return (
                <Space size={4}>
                  <Typography.Text type="secondary">
                    {item.isNew ? '—' : current?.seamQuantity ?? '—'}
                  </Typography.Text>
                  <span>→</span>
                  <InputNumber
                    min={0}
                    precision={0}
                    disabled={confirmed || item.removed}
                    value={item.removed ? 0 : item.seamQuantity ?? undefined}
                    onChange={value => patchItem(item.key, { seamQuantity: value ?? null })}
                  />
                </Space>
              );
            },
          },
          {
            title: '成交价（前 → 后）',
            key: 'unitPrice',
            width: 190,
            render: (_, item) => {
              const current = orderItems.find(entry => entry.id === item.orderItemId);
              return (
                <Space size={4}>
                  <Typography.Text type="secondary">{item.isNew ? '—' : current?.unitPrice ?? '—'}</Typography.Text>
                  <span>→</span>
                  <Input
                    style={{ width: 90 }}
                    disabled={confirmed || item.removed}
                    value={item.unitPrice ?? ''}
                    onChange={event => patchItem(item.key, { unitPrice: event.target.value })}
                  />
                </Space>
              );
            },
          },
          {
            title: '已发货下限',
            key: 'shipped',
            width: 110,
            render: (_, item) => {
              const check = checks[items.indexOf(item)];
              if (!check || item.isNew) {
                return '—';
              }
              return check.belowShipped ? <Tag color="red">{check.shipped}（已低于）</Tag> : check.shipped;
            },
          },
          {
            title: '操作',
            key: 'action',
            width: 110,
            render: (_, item) =>
              item.isNew ? (
                <Button
                  type="link"
                  size="small"
                  danger
                  disabled={confirmed}
                  onClick={() => setItems(previous => previous.filter(entry => entry.key !== item.key))}
                >
                  删除
                </Button>
              ) : (
                <Button
                  type="link"
                  size="small"
                  disabled={confirmed}
                  onClick={() => patchItem(item.key, { removed: !item.removed })}
                >
                  {item.removed ? '撤销移除' : '改为移除'}
                </Button>
              ),
          },
        ]}
      />
      {!confirmed && (
        <Button
          style={{ marginTop: 12 }}
          onClick={() =>
            setItems(previous => [
              ...previous,
              { key: crypto.randomUUID(), isNew: true, quantity: 1, seamQuantity: 0 },
            ])
          }
        >
          新增明细
        </Button>
      )}
      <Alert
        style={{ marginTop: 12 }}
        type="info"
        showIcon
        title="未改动的行不会写入变更单；移除＝该明细数量归零（保留行与历史，不物理删除）；新增明细的缝边种类与收费默认取商品的默认值。"
      />

      <Divider titlePlacement="start">在制/合格超出处理</Divider>
      {checks.filter(check => check.surplus > 0).length === 0 ? (
        <Alert type="info" showIcon title="本次变更没有超出在制/合格数量的明细，无需逐项处理" />
      ) : (
        checks.map((check, index) => {
          if (check.surplus <= 0) {
            return null;
          }
          const item = items[index];
          return (
            <Card
              key={item.key}
              size="small"
              style={{ marginBottom: 12 }}
              title={`#${check.current?.lineNo} ${check.current?.productNo} ${check.current?.productName}：新需求 ${check.target}，在制/合格 ${check.inProcess}，超出 ${check.surplus}`}
            >
              <Space orientation="vertical" style={{ width: '100%' }}>
                <Radio.Group
                  disabled={confirmed}
                  value={item.surplusDisposition}
                  onChange={event => patchItem(item.key, { surplusDisposition: event.target.value })}
                  options={[
                    { value: 'FINISH_TO_SURPLUS', label: '继续完成并转成品余量' },
                    { value: 'SCRAP', label: '立即报废' },
                  ]}
                />
                <Space>
                  <span>处理数量</span>
                  <InputNumber
                    min={check.surplus}
                    precision={0}
                    disabled={confirmed}
                    value={item.surplusQuantity}
                    onChange={value => patchItem(item.key, { surplusQuantity: value ?? undefined })}
                  />
                  <span>（不得少于超出量 {check.surplus}）</span>
                </Space>
                <Input
                  placeholder="处理原因（必填）"
                  disabled={confirmed}
                  value={item.surplusReason}
                  onChange={event => patchItem(item.key, { surplusReason: event.target.value })}
                />
              </Space>
            </Card>
          );
        })
      )}

      <Divider titlePlacement="start">表头与原因（留空＝沿用原值）</Divider>
      <Row gutter={16}>
        <Col span={6}>
          <Typography.Text type="secondary">变更原因</Typography.Text>
          <Input
            style={{ marginTop: 4 }}
            disabled={confirmed}
            value={reason}
            placeholder="记入变更单，便于追溯"
            onChange={event => setReason(event.target.value)}
          />
        </Col>
        <Col span={6}>
          <Typography.Text type="secondary">变更后交期（当前 {order.expectedDeliveryDate ?? '—'}）</Typography.Text>
          <DatePicker
            style={{ marginTop: 4, width: '100%' }}
            disabled={confirmed}
            value={headerDraft.expectedDeliveryDate ?? null}
            onChange={value => setHeaderDraft(previous => ({ ...previous, expectedDeliveryDate: value }))}
          />
        </Col>
        <Col span={6}>
          <Typography.Text type="secondary">变更后整单优惠（当前 {order.discountAmount}）</Typography.Text>
          <Input
            style={{ marginTop: 4 }}
            disabled={confirmed}
            value={headerDraft.discountAmount ?? ''}
            placeholder="如 5.0000"
            onChange={event => setHeaderDraft(previous => ({ ...previous, discountAmount: event.target.value }))}
          />
        </Col>
        <Col span={6}>
          <Typography.Text type="secondary">变更后备注（当前 {order.note || '—'}）</Typography.Text>
          <Input
            style={{ marginTop: 4 }}
            disabled={confirmed}
            value={headerDraft.note ?? ''}
            onChange={event => setHeaderDraft(previous => ({ ...previous, note: event.target.value }))}
          />
        </Col>
        <Col span={6}>
          <Typography.Text type="secondary">变更后收货人（当前 {order.recipientName}）</Typography.Text>
          <Input
            style={{ marginTop: 4 }}
            disabled={confirmed}
            value={headerDraft.recipientName ?? ''}
            onChange={event => setHeaderDraft(previous => ({ ...previous, recipientName: event.target.value }))}
          />
        </Col>
        <Col span={6}>
          <Typography.Text type="secondary">变更后收货电话（当前 {order.recipientPhone}）</Typography.Text>
          <Input
            style={{ marginTop: 4 }}
            disabled={confirmed}
            value={headerDraft.recipientPhone ?? ''}
            onChange={event => setHeaderDraft(previous => ({ ...previous, recipientPhone: event.target.value }))}
          />
        </Col>
        <Col span={6}>
          <Typography.Text type="secondary">变更后地区（当前 {order.region}）</Typography.Text>
          <Input
            style={{ marginTop: 4 }}
            disabled={confirmed}
            value={headerDraft.region ?? ''}
            onChange={event => setHeaderDraft(previous => ({ ...previous, region: event.target.value }))}
          />
        </Col>
        <Col span={6}>
          <Typography.Text type="secondary">变更后地址（当前 {order.address}）</Typography.Text>
          <Input
            style={{ marginTop: 4 }}
            disabled={confirmed}
            value={headerDraft.address ?? ''}
            onChange={event => setHeaderDraft(previous => ({ ...previous, address: event.target.value }))}
          />
        </Col>
      </Row>

      <Divider titlePlacement="start">其他影响</Divider>
      <Row gutter={16}>
        <Col span={12}>
          <Alert
            type="info"
            showIcon
            title="待退款影响"
            description={`当前应收 ${order.receivableAmount}；变更确认后若有效应收低于已登记收款与已登记变更退款之差，将产生待退款并阻止关闭。收款与退款在阶段七实现，本页只读提示、不做金额计算。`}
          />
        </Col>
        <Col span={12}>
          <Alert
            type="warning"
            showIcon
            title="确认后不可覆盖的历史"
            description="确认会写入 ORDER_CHANGE 履约事实并同步需求投影与订单金额，订单主状态不变；已确认的变更单、快照与履约事实均不可修改或删除，只能通过新的变更或更正事实处理。"
          />
        </Col>
      </Row>
    </Card>
  );
}
