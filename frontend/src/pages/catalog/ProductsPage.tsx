import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  Form,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd';
import {
  createProduct,
  getProduct,
  listProducts,
  previewNewProduct,
  previewProductUpdate,
  toggleProduct,
  updateProduct,
  type ProductDetail,
  type ProductPreviewInput,
  type ProductPreviewResult,
  type ProductSummary,
  type SeamBudget,
} from '../../api/catalog';
import { getSettings, type SettingsValues, type StarLevel, type PackagingTier } from '../../api/settings';
import { listStaticDataItems, type StaticDataItem } from '../../api/staticData';
import { describeApiError } from '../../api/errors';
import { createPreviewScheduler, type PreviewSnapshot } from './previewScheduler';
import { collectPreviewInput, isSeamCleared, previewFromDetail, type PreviewFormValues } from './previewInput';

type Mode = { kind: 'list' } | { kind: 'create' } | { kind: 'edit'; id: number };

const EMPTY_PREVIEW: PreviewSnapshot<ProductPreviewResult> = { status: 'idle', result: null };

export function ProductsPage() {
  const { message } = App.useApp();
  const [mode, setMode] = useState<Mode>({ kind: 'list' });
  const [rows, setRows] = useState<ProductSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [statusFilter, setStatusFilter] = useState<string | undefined>();
  const [nameFilter, setNameFilter] = useState('');

  async function reload(status?: string, name?: string) {
    setLoading(true);
    try {
      setRows(await listProducts({ status, name: name || undefined }));
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void reload();
  }, []);

  async function onToggle(row: ProductSummary) {
    try {
      await toggleProduct(row.id, row.status === 'ACTIVE' ? 'disable' : 'enable');
      message.success(row.status === 'ACTIVE' ? '商品已停用' : '商品已启用');
      void reload(statusFilter, nameFilter);
    } catch (error) {
      message.error(describeApiError(error));
    }
  }

  if (mode.kind !== 'list') {
    return (
      <ProductEditor
        mode={mode}
        onDone={() => {
          setMode({ kind: 'list' });
          void reload(statusFilter, nameFilter);
        }}
      />
    );
  }

  return (
    <Card>
      <Typography.Title level={4} style={{ marginTop: 0 }}>
        商品
      </Typography.Title>
      <Space.Compact style={{ marginBottom: 12 }}>
        <Select
          placeholder="状态"
          style={{ width: 120 }}
          allowClear
          value={statusFilter}
          onChange={value => setStatusFilter(value)}
          options={[
            { value: 'ACTIVE', label: '启用' },
            { value: 'DISABLED', label: '停用' },
          ]}
        />
        <Input
          placeholder="商品名称"
          value={nameFilter}
          onChange={e => setNameFilter(e.target.value)}
          onPressEnter={() => void reload(statusFilter, nameFilter)}
          allowClear
        />
        <Button onClick={() => void reload(statusFilter, nameFilter)}>查询</Button>
      </Space.Compact>
      <Button type="primary" style={{ marginLeft: 8 }} onClick={() => setMode({ kind: 'create' })}>
        新建商品
      </Button>
      <Table<ProductSummary>
        style={{ marginTop: 12 }}
        size="small"
        rowKey="productNo"
        loading={loading}
        dataSource={rows}
        pagination={false}
        columns={[
          { title: '商品编号', dataIndex: 'productNo', width: 100 },
          { title: '名称', dataIndex: 'name' },
          { title: '星级', dataIndex: 'starName', width: 90, render: name => <Tag>{name}</Tag> },
          { title: '销售单价', dataIndex: 'salePrice', width: 110, align: 'right' },
          { title: '单件总成本', dataIndex: 'totalCost', width: 110, align: 'right' },
          {
            title: '状态',
            dataIndex: 'status',
            width: 80,
            render: status => (
              <Tag color={status === 'ACTIVE' ? 'green' : 'default'}>{status === 'ACTIVE' ? '启用' : '停用'}</Tag>
            ),
          },
          {
            title: '操作',
            key: 'action',
            width: 150,
            render: (_, row) => (
              <Space size={4}>
                <Button type="link" size="small" onClick={() => setMode({ kind: 'edit', id: row.id })}>
                  编辑
                </Button>
                <Button type="link" size="small" danger={row.status === 'ACTIVE'} onClick={() => void onToggle(row)}>
                  {row.status === 'ACTIVE' ? '停用' : '启用'}
                </Button>
              </Space>
            ),
          },
        ]}
      />
    </Card>
  );
}

function ProductEditor({ mode, onDone }: { mode: Exclude<Mode, { kind: 'list' }>; onDone: () => void }) {
  const { message } = App.useApp();
  const [form] = Form.useForm();
  const [detail, setDetail] = useState<ProductDetail | null>(null);
  const [starLevels, setStarLevels] = useState<StarLevel[]>([]);
  const [tiers, setTiers] = useState<PackagingTier[]>([]);
  const [seamTypes, setSeamTypes] = useState<StaticDataItem[]>([]);
  const [settings, setSettings] = useState<SettingsValues | null>(null);
  const [ready, setReady] = useState(false);
  const [saving, setSaving] = useState(false);
  const [preview, setPreview] = useState<PreviewSnapshot<ProductPreviewResult>>(EMPTY_PREVIEW);
  const [refreshReferences, setRefreshReferences] = useState(false);
  const editing = mode.kind === 'edit';
  const watched = Form.useWatch([], form);
  const modeRef = useRef(mode);
  const detailRef = useRef<ProductDetail | null>(null);
  modeRef.current = mode;
  detailRef.current = detail;

  // 试算来源只有服务端：本地不再保留任何公式副本
  const scheduler = useMemo(
    () =>
      createPreviewScheduler<ProductPreviewInput, ProductPreviewResult>({
        load: (input, signal) => {
          const current = modeRef.current;
          return current.kind === 'edit'
            ? previewProductUpdate(current.id, { ...input, version: detailRef.current!.version }, signal)
            : previewNewProduct(input, signal);
        },
        onChange: setPreview,
      }),
    [],
  );

  useEffect(() => {
    (async () => {
      try {
        const [global, seamCatalog] = await Promise.all([
          getSettings(),
          listStaticDataItems('SEAM_TYPE'),
        ]);
        setStarLevels(global.starLevels);
        setTiers(global.packagingTiers);
        setSeamTypes(seamCatalog);
        setSettings(global.values);
        if (editing) {
          const data = await getProduct(mode.id);
          setDetail(data);
          form.setFieldsValue({
            ...data,
            packagingTierId: data.packagingTierId ?? undefined,
            seamTypeId: data.seamTypeId ?? undefined,
          });
        } else {
          form.setFieldsValue({
            boxLaborFee: global.values.boxLaborDefault,
            transportPackingFee: global.values.transportPackingDefault,
            packagingCommission: global.values.packagingCommissionDefault,
            moldAmortFee: '0.0000',
          });
        }
        setReady(true);
      } catch (error) {
        message.error(describeApiError(error));
      }
    })();
  }, [editing, mode]);

  useEffect(() => {
    if (!ready) {
      return;
    }
    // 只有计算字段变化才触发：名称/备注等无关字段不会改变收集结果
    const collected = collectPreviewInput((watched ?? {}) as PreviewFormValues);
    scheduler.request(collected === null ? null : { ...collected, refreshGlobalReferences: refreshReferences });
  }, [ready, watched, scheduler, refreshReferences]);

  useEffect(() => () => scheduler.cancel(), [scheduler]);

  // 每日最大产能是服务端派生值，表单只做即时展示提示，不作为可提交字段
  const watchedValues = (watched ?? {}) as { moldQuantity?: number; dailyBatchLimit?: number };
  const moldQuantityValue = Number(watchedValues.moldQuantity ?? 0);
  const dailyBatchLimitValue = Number(watchedValues.dailyBatchLimit ?? 0);
  const dailyMaxCapacityText =
    moldQuantityValue > 0 && dailyBatchLimitValue > 0
      ? String(moldQuantityValue * dailyBatchLimitValue)
      : '待填写';

  // 商品快照与当前全局设置的差异：驱动标签标注与“刷新为最新设置”入口（口径为不自动回溯）
  const currentStar = editing && detail ? starLevels.find(level => level.id === detail.starLevelId) : undefined;
  const starDrifted = Boolean(
    !refreshReferences && currentStar && detail && currentStar.stdMinutes !== detail.starStdMinutes,
  );
  const currentTier = editing && detail && detail.packagingTierId !== null && detail.packagingTierId !== undefined
    ? tiers.find(tier => tier.id === detail.packagingTierId)
    : undefined;
  const tierDrifted = Boolean(
    !refreshReferences && currentTier && detail
    && detail.packagingStdMinutes !== undefined && detail.packagingCommission !== undefined
    && (Number(currentTier.stdMinutes) !== Number(detail.packagingStdMinutes)
      ),
  );
  const glueDrifted = Boolean(
    !refreshReferences && detail && settings && Number(settings.glueUnitPrice) !== Number(detail.glueUnitPrice),
  );
  const colorpasteDrifted = Boolean(
    !refreshReferences && detail && settings
    && Number(settings.colorpasteUnitPrice) !== Number(detail.colorpasteUnitPrice),
  );
  const sundriesDrifted = Boolean(
    !refreshReferences && detail && settings
    && Number(settings.sundriesDefault) !== Number(detail.dailySundriesFee),
  );
  const rentDrifted = Boolean(
    !refreshReferences && detail && settings
    && Number(settings.rentUtilitiesDefault) !== Number(detail.rentUtilitiesFee),
  );
  const currentSeam = editing && detail && detail.seamTypeId !== null && detail.seamTypeId !== undefined
    ? seamTypes.find(type => type.id === detail.seamTypeId)
    : undefined;
  const seamDrifted = Boolean(
    !refreshReferences && currentSeam && detail
    && detail.seamStdMinutes !== undefined && currentSeam.stdMinutes !== undefined
    && Number(currentSeam.stdMinutes) !== Number(detail.seamStdMinutes),
  );

  // 商品自身引用的星级/档位按冻结快照展示；仅在与当前全局不一致时标注（商品快照）
  const starOptions = starLevels.map(level =>
    editing && detail && level.id === detail.starLevelId
      ? {
          value: level.id,
          label: starDrifted
            ? `${detail.starName} · ${detail.starStdMinutes}分钟（商品快照）`
            : `${detail.starName} · ${detail.starStdMinutes}分钟`,
        }
      : { value: level.id, label: `${level.name} · ${level.stdMinutes}分钟` },
  );
  const tierOptions = tiers.map(tier =>
    editing && detail && detail.packagingTierId === tier.id
      ? {
          value: tier.id,
          label: tierDrifted
            ? `${detail.packagingTierName} · ${detail.packagingStdMinutes}分钟 +${detail.packagingCommission}（商品快照）`
            : `${tier.tierName} · ${tier.stdMinutes}分钟`,
        }
      : { value: tier.id, label: `${tier.tierName} · ${tier.stdMinutes}分钟` },
  );
  const seamOptions = seamTypes.map(type =>
    editing && detail && detail.seamTypeId === type.id && seamDrifted
      ? {
          value: type.id,
          label: `${detail.seamTypeName} · 商品快照 ${detail.seamStdMinutes} 分钟`,
        }
      : { value: type.id, label: `${type.name} · 标准 ${type.stdMinutes} 分钟` },
  );

  const globalDrifts: string[] = [];
  if (starDrifted && currentStar && detail) {
    globalDrifts.push(`星级「${currentStar.name}」标准时长 ${detail.starStdMinutes} → ${currentStar.stdMinutes} 分钟`);
  }
  if (tierDrifted && currentTier && detail) {
    globalDrifts.push(
      `档位「${currentTier.tierName}」${detail.packagingStdMinutes} → ${currentTier.stdMinutes} 分钟`,
    );
  }
  if (glueDrifted && detail && settings) {
    globalDrifts.push(`胶水单价 ${detail.glueUnitPrice} → ${settings.glueUnitPrice}`);
  }
  if (colorpasteDrifted && detail && settings) {
    globalDrifts.push(`色浆单价 ${detail.colorpasteUnitPrice} → ${settings.colorpasteUnitPrice}`);
  }
  if (sundriesDrifted && detail && settings) {
    globalDrifts.push(`日常杂费 ${detail.dailySundriesFee} → ${settings.sundriesDefault}`);
  }
  if (rentDrifted && detail && settings) {
    globalDrifts.push(`房租水电费 ${detail.rentUtilitiesFee} → ${settings.rentUtilitiesDefault}`);
  }
  if (seamDrifted && currentSeam && detail) {
    globalDrifts.push(
      `缝边种类「${currentSeam.name}」标准时长 ${detail.seamStdMinutes} → ${currentSeam.stdMinutes} 分钟`,
    );
  }

  async function submit() {
    const values = await form.validateFields();
    setSaving(true);
    const previewedTotal = preview.result?.totalCost;
    try {
      const saved = editing
        ? await updateProduct(mode.id, {
            ...values,
            packagingTierId: values.packagingTierId ?? null,
            seamTypeId: values.seamTypeId ?? null,
            // PATCH 中 null 与“未提供”无法区分，清空默认缝边剪袋类型必须显式声明
            clearSeamType: isSeamCleared(values),
            version: detail!.version,
            reason: values.reason,
            refreshGlobalReferences: refreshReferences,
          })
        : await createProduct(values);
      // 保存结果覆盖预估并取消在途试算，避免迟到响应覆盖
      scheduler.showSaved(previewFromDetail(saved));
      if (previewedTotal && previewedTotal !== saved.totalCost) {
        message.warning(
          `配置已变化，已按服务端最新配置保存：单件总成本 ${saved.totalCost}（预估 ${previewedTotal}）`,
        );
      } else {
        message.success(editing ? '商品已保存，成本快照已按服务端重算' : '商品已创建');
      }
      onDone();
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setSaving(false);
    }
  }

  if (!ready) {
    return (
      <Card loading>
        <Typography.Title level={4} style={{ marginTop: 0 }}>
          {editing ? '编辑商品' : '新建商品'}
        </Typography.Title>
      </Card>
    );
  }

  return (
    <Row gutter={24}>
      <Col span={16}>
        <Card>
          <Typography.Title level={4} style={{ marginTop: 0 }}>
            {editing ? `编辑商品 ${detail?.productNo ?? ''}` : '新建商品'}
          </Typography.Title>
          {globalDrifts.length > 0 && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12 }}
              title="全局设置已变化，本商品仍按保存时的快照计算"
              description={globalDrifts.join('；')}
              action={
                <Button size="small" onClick={() => setRefreshReferences(true)}>
                  刷新为最新设置
                </Button>
              }
            />
          )}
          <Form form={form} layout="vertical">
            <Row gutter={16}>
              <Col span={16}>
                <Form.Item
                  name="name"
                  label="商品名称"
                  rules={[{ required: true, whitespace: true, message: '请输入商品名称' }]}
                >
                  <Input placeholder="如：泰迪熊 30cm（允许重名，仅作识别）" />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item
                  name="starLevelId"
                  label="星级（标准工作量）"
                  rules={[{ required: true, message: '请选择星级' }]}
                >
                  <Select
                    placeholder="请选择星级"
                    options={starOptions}
                  />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="salePrice" label="销售单价（元）" rules={[{ required: true, message: '请输入销售单价' }]}>
                  <Input placeholder="如 30.0000，可为 0（赠品/样品）" />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="weightG" label="商品克重（g）" rules={[{ required: true, message: '请输入克重' }]}>
                  <InputNumber min={0} precision={0} style={{ width: '100%' }} placeholder="单位 g，如 270" />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item label="胶水损耗率（%）">
                  <GlobalValue value={settings?.lossRateDefault} />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item
                  name="packagingTierId"
                  label="包装档位"
                >
                  <Select
                    allowClear
                    placeholder="请选择包装档位（可空，先在设置里维护）"
                    options={tierOptions}
                  />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="packagingCommission" label="包装提成（元/件）">
                  <Input placeholder="如 0.3000，可为 0" />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="boxLaborFee" label="单件装箱人工费（元）">
                  <Input placeholder="如 0.5000（默认取全局）" />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="transportPackingFee" label="运输包装费（元）">
                  <Input placeholder="如 0.3000（默认取全局）" />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item label="日常杂费（元）">
                  <GlobalValue value={settings?.sundriesDefault} />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item label="房租水电费（元）">
                  <GlobalValue value={settings?.rentUtilitiesDefault} />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="moldAmortFee" label="模具摊销费（元）">
                  <Input placeholder="如 0.0000（无全局默认）" />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item
                  name="moldQuantity"
                  label="模具数量（件/模）"
                  rules={[{ required: true, message: '请输入模具数量' }]}
                  extra="同一模具批次可并行生产的数量，正整数"
                >
                  <InputNumber min={1} precision={0} style={{ width: '100%' }} placeholder="如 10" />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item
                  name="dailyBatchLimit"
                  label="每日批次数（次/天）"
                  rules={[{ required: true, message: '请输入每日批次数' }]}
                  extra={`每日最大产能 ${dailyMaxCapacityText} 件（模具数量 × 每日批次数，服务端派生）`}
                >
                  <InputNumber min={1} precision={0} style={{ width: '100%' }} placeholder="如 6" />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item
                  name="seamTypeId"
                  label="默认缝边剪袋类型"
                  extra="订单新建明细时的默认值，订单可改；留空＝默认不缝边剪袋"
                >
                  <Select
                    allowClear
                    placeholder="不缝边剪袋（先在设置里维护缝边种类）"
                    options={seamOptions}
                  />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="seamFee" label="缝边价格（元/件）">
                  <Input placeholder="如 2.0000，订单缝边收费的默认值" />
                </Form.Item>
              </Col>
              <Col span={24}>
                <Form.Item name="note" label="商品说明">
                  <Input.TextArea rows={2} placeholder="外观、制作注意事项、识别信息（可空，不影响计算）" />
                </Form.Item>
              </Col>
              {editing && (
                <Col span={24}>
                  <Form.Item name="reason" label="修改原因（可选）">
                    <Input placeholder="会记入变更日志，便于日后追溯" />
                  </Form.Item>
                </Col>
              )}
            </Row>
            <Space>
              <Button type="primary" loading={saving} onClick={() => void submit()}>
                {editing ? '保存修改' : '创建商品'}
              </Button>
              <Button onClick={onDone}>返回列表</Button>
            </Space>
          </Form>
        </Card>
      </Col>
      <Col span={8}>
        <Card
          style={{ position: 'sticky', top: 16 }}
          title={
            <Space>
              利润预估
              <Tag color="blue">服务端试算 · 保存以服务端快照为准</Tag>
            </Space>
          }
        >
          <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
            全局：胶水 {settings?.glueUnitPrice ?? '0.0000'} 元/g、色浆 {settings?.colorpasteUnitPrice ?? '0.0000'} 元/g
          </Typography.Paragraph>
          <Typography.Paragraph style={{ marginBottom: 8 }}>
            {preview.status === 'idle' && <Tag>待填写</Tag>}
            {preview.status === 'loading' && (
              <Tag color="processing">
                <Spin size="small" /> 计算中
              </Tag>
            )}
            {preview.status === 'failed' && (
              <Space size={4}>
                <Tag color="warning">试算失败</Tag>
                <Button size="small" onClick={() => scheduler.retry()}>
                  重试
                </Button>
              </Space>
            )}
            {preview.status === 'ready' && <Tag color="green">已按服务端试算</Tag>}
          </Typography.Paragraph>
          <table className="preview-table">
            <tbody>
              <tr><td>胶水克重</td><td>{cell(preview, 'glueGrams', ' g')}</td></tr>
              <tr><td>胶水成本</td><td>{cell(preview, 'glueCost')}</td></tr>
              <tr><td>色浆成本</td><td>{cell(preview, 'colorpasteCost')}</td></tr>
              <tr><td>材料成本</td><td><b>{cell(preview, 'materialCost')}</b></td></tr>
              <tr>
                <td>
                  制品人工费（工作日 {trimmed(settings?.workdayHours)}h×
                  {preview.result?.qty8h ?? '—'}件 / 有效工时{' '}
                  {trimmed(effectiveHours(settings))}h×{preview.result?.qty6h ?? '—'}件）
                </td>
                <td>{cell(preview, 'productLaborFee')}</td>
              </tr>
              <tr><td>包装人工费</td><td>{cell(preview, 'packagingLaborFee')}</td></tr>
              <tr><td>装箱人工费</td><td>{cell(preview, 'boxLaborFee')}</td></tr>
              <tr><td>人工成本</td><td><b>{cell(preview, 'laborCost')}</b></td></tr>
              <tr><td>其他成本（运输/杂费/房租/模具）</td><td><b>{cell(preview, 'otherCost')}</b></td></tr>
              <tr><td>单件总成本（不缝边剪袋）</td><td><b>{cell(preview, 'totalCost')}</b></td></tr>
              <tr><td>参考售价（不缝边剪袋，按目标利润率 {trimmed(settings?.targetMarginRate)}%）</td><td>{cell(preview, 'referencePrice')}</td></tr>
              <tr><td>预计利润</td><td><b>{cell(preview, 'estimatedProfit')}</b></td></tr>
              <tr><td>预计利润率</td><td><b>{cell(preview, 'estimatedMarginRatePercent')}</b></td></tr>
              <tr>
                <td className="preview-section" colSpan={2}>缝边剪袋变体（含缝边成本，单件）</td>
              </tr>
              <tr><td>单件缝边人工成本（元/件，缝边标准分钟 × 时薪 ÷ 60）</td><td>{seamCell(preview, 'seamUnitCost')}</td></tr>
              <tr><td>缝边价格（元/件）</td><td>{seamCell(preview, 'seamFee')}</td></tr>
              <tr><td>缝边剪袋变体总成本</td><td><b>{seamCell(preview, 'totalCost')}</b></td></tr>
              <tr><td>缝边剪袋变体参考售价</td><td>{seamCell(preview, 'referencePrice')}</td></tr>
            </tbody>
          </table>
          <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
            未选默认缝边剪袋类型＝默认不缝边剪袋，此时无缝边剪袋变体；商品自身成本与参考售价始终按不缝边剪袋口径保存。
          </Typography.Paragraph>
        </Card>
      </Col>
    </Row>
  );
}

/** 全局口径字段只读展示：值由服务端在保存时取当前全局设置，商品侧不可修改。 */
function GlobalValue({ value }: { value?: string }) {
  return (
    <Space size={4}>
      <Typography.Text>{value ?? '—'}</Typography.Text>
      <Typography.Text type="secondary">全局设置</Typography.Text>
    </Space>
  );
}

/** 只有当前有效的服务端结果才展示数值，其余状态一律显示占位符，不伪造零值。 */
function cell(
  snapshot: PreviewSnapshot<ProductPreviewResult>,
  field: keyof ProductPreviewResult,
  suffix = '',
): string {
  if (snapshot.status !== 'ready' || snapshot.result === null) {
    return '—';
  }
  return `${snapshot.result[field]}${suffix}`;
}

/** 缝边剪袋变体字段：未选默认缝边剪袋类型（服务端返回 null）时不展示数值。 */
function seamCell(
  snapshot: PreviewSnapshot<ProductPreviewResult>,
  field: keyof SeamBudget,
  suffix = '',
): string {
  if (snapshot.status !== 'ready' || snapshot.result === null || snapshot.result.seamBudget === null) {
    return '—';
  }
  return `${snapshot.result.seamBudget[field]}${suffix}`;
}

/** 展示用数值：去掉多余小数（8 → 8、7.5 → 7.5、30.000000 → 30）。 */
function trimmed(value: string | undefined): string {
  return value === undefined ? '—' : String(Number(Number(value).toFixed(2)));
}

/** 制品有效工时 = 工作日小时数 × 制品有效工时率（仅用于展示标签，成本一律由服务端计算）。 */
function effectiveHours(settings: SettingsValues | null): string {
  if (!settings) {
    return '—';
  }
  return String(Number((Number(settings.workdayHours) * Number(settings.makingEffectiveHourRate)).toFixed(2)));
}
