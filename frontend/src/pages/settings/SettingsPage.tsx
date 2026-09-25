import { useEffect, useState } from 'react';
import {
  App,
  Button,
  Card,
  Col,
  Form,
  Input,
  Modal,
  Popconfirm,
  Row,
  Space,
  Table,
  Tag,
  Tabs,
  Typography,
  type TableColumnsType,
} from 'antd';
import {
  getFormulas,
  getSettings,
  patchSettings,
  type FormulaEntry,
  type FormulaGroup,
  type SettingsValues,
} from '../../api/settings';
import {
  createStaticDataItem,
  deleteStaticDataItem,
  listStaticDataCategories,
  listStaticDataItems,
  updateStaticDataItem,
  type StaticDataCategory,
  type StaticDataItem,
} from '../../api/staticData';
import { describeApiError } from '../../api/errors';

/**
 * 全局设置（任务 2.20/2.25）：Tab「单价与默认值」/「静态数据」/「公式说明」。
 * 静态数据 Tab 先列系统固定类别（不可增删改名，标「系统内置」），点「编辑」用弹窗管理该类别的条目；
 * 员工工种为系统预置四道工序（标「系统预置」，只能改名）；星级/包装档位/缝边都只填整数标准分钟。
 */
export function SettingsPage() {
  const { message } = App.useApp();
  const [valuesForm] = Form.useForm();
  const [itemForm] = Form.useForm();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [values, setValues] = useState<SettingsValues | null>(null);
  const [formulaGroups, setFormulaGroups] = useState<FormulaGroup[]>([]);
  const [categories, setCategories] = useState<StaticDataCategory[]>([]);
  const [active, setActive] = useState<StaticDataCategory | null>(null);
  const [items, setItems] = useState<StaticDataItem[]>([]);
  const [itemModal, setItemModal] = useState<{ open: boolean; id?: number }>({ open: false });
  // 条目弹窗按 destroyOnHidden 卸载重建：初值等弹窗打开、Form 挂载后再注入，
  // 在卸载状态下调用 setFieldsValue/resetFields 会触发 antd「useForm 未连接 Form」告警。
  const [itemDraft, setItemDraft] = useState<Record<string, unknown> | undefined>();

  async function reload() {
    setLoading(true);
    try {
      const settings = await getSettings();
      setValues(settings.values);
      valuesForm.setFieldsValue(settings.values);
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setLoading(false);
    }
  }

  async function reloadCategories() {
    try {
      setCategories(await listStaticDataCategories());
    } catch (error) {
      message.error(describeApiError(error));
    }
  }

  useEffect(() => {
    void reload();
    void reloadCategories();
  }, []);

  // 弹窗打开、Form 挂载后注入条目初值；关闭时不动表单，避免卸载后调用表单方法
  useEffect(() => {
    if (!itemModal.open) {
      return;
    }
    itemForm.resetFields();
    if (itemDraft) {
      itemForm.setFieldsValue(itemDraft);
    }
  }, [itemModal.open, itemDraft, itemForm]);

  // 只读公式目录：一次加载即可
  useEffect(() => {
    (async () => {
      try {
        setFormulaGroups((await getFormulas()).groups);
      } catch (error) {
        message.error(describeApiError(error));
      }
    })();
  }, []);

  async function saveValues() {
    const formValues = await valuesForm.validateFields();
    setSaving(true);
    try {
      const updated = await patchSettings(formValues);
      setValues(updated);
      valuesForm.setFieldsValue(updated);
      message.success('全局设置已保存（不影响既有商品快照）');
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setSaving(false);
    }
  }

  async function openCategory(category: StaticDataCategory) {
    try {
      setItems(await listStaticDataItems(category.code));
      setActive(category);
    } catch (error) {
      message.error(describeApiError(error));
    }
  }

  async function saveItem() {
    if (!active) {
      return;
    }
    const body = await itemForm.validateFields();
    setSaving(true);
    try {
      const payload = {
        name: body.name,
        stdMinutes: body.stdMinutes === undefined || body.stdMinutes === null ? undefined : String(body.stdMinutes),
      };
      if (itemModal.id) {
        await updateStaticDataItem(active.code, itemModal.id, payload);
        message.success('条目已更新（不影响既有快照）');
      } else {
        await createStaticDataItem(active.code, payload);
        message.success('条目已创建');
      }
      setItemModal({ open: false });
      setItems(await listStaticDataItems(active.code));
      await reloadCategories();
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setSaving(false);
    }
  }

  async function removeItem(id: number) {
    if (!active) {
      return;
    }
    try {
      await deleteStaticDataItem(active.code, id);
      message.success('条目已删除');
      setItems(await listStaticDataItems(active.code));
      await reloadCategories();
    } catch (error) {
      message.error(describeApiError(error));
    }
  }

  const isWorkType = active?.code === 'WORK_TYPE';

  const itemColumns: TableColumnsType<StaticDataItem> = [
    ...(isWorkType ? [{ title: '系统标识', dataIndex: 'code', width: 150 }] : []),
    {
      title: '名称',
      dataIndex: 'name',
      render: (value: string) => (
        <Space size={6}>
          <span>{value}</span>
          {isWorkType && <Tag color="blue">系统预置</Tag>}
        </Space>
      ),
    },
    ...(isWorkType
      ? []
      : [
          {
            title: '标准时长（分钟）',
            key: 'value',
            width: 180,
            render: (_: unknown, row: StaticDataItem) => row.stdMinutes,
          },
        ]),
    {
      title: '操作',
      key: 'action',
      width: 140,
      render: (_: unknown, row: StaticDataItem) => (
        <Space size={4}>
          <Button
            type="link"
            size="small"
            onClick={() => {
              setItemDraft({
                name: row.name,
                stdMinutes: row.stdMinutes,
              });
              setItemModal({ open: true, id: row.id });
            }}
          >
            编辑
          </Button>
          {!isWorkType && (
            <Popconfirm title="删除该条目？被引用时不允许删除" onConfirm={() => void removeItem(row.id)}>
              <Button type="link" size="small" danger>
                删除
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <Card loading={loading}>
      <Typography.Title level={4} style={{ marginTop: 0 }}>
        全局设置
      </Typography.Title>
      <Tabs
        items={[
          {
            key: 'values',
            label: '单价与默认值',
            children: (
              <>
                <Typography.Paragraph type="secondary">
                  全局变更只影响以后新建或重新选择引用的商品，不回溯既有商品快照；商品侧只读使用的字段在保存时按当时全局值冻结。
                </Typography.Paragraph>
                <Form form={valuesForm} layout="vertical">
                  <Row gutter={16}>
                    <Col span={8}>
                      <Form.Item name="glueUnitPrice" label="胶水单价（元/g）" rules={[{ required: true }]}>
                        <Input placeholder="如 0.0100，支持 0.0024 级精度" />
                      </Form.Item>
                    </Col>
                    <Col span={8}>
                      <Form.Item name="colorpasteUnitPrice" label="色浆单价（元/g）" rules={[{ required: true }]}>
                        <Input placeholder="如 0.0200" />
                      </Form.Item>
                    </Col>
                    <Col span={8}>
                      <Form.Item name="lossRateDefault" label="胶水损耗率默认（%）" rules={[{ required: true }]}>
                        <Input placeholder="如 20.5（商品只读使用）" />
                      </Form.Item>
                    </Col>
                    <Col span={8}>
                      <Form.Item name="boxLaborDefault" label="单件装箱人工费默认（元）" rules={[{ required: true }]}>
                        <Input placeholder="如 0.5000（商品可改）" />
                      </Form.Item>
                    </Col>
                    <Col span={8}>
                      <Form.Item
                        name="transportPackingDefault"
                        label="运输包装费默认（元）"
                        rules={[{ required: true }]}
                      >
                        <Input placeholder="如 0.3000（商品可改）" />
                      </Form.Item>
                    </Col>
                    <Col span={8}>
                      <Form.Item name="packagingCommissionDefault" label="包装提成默认（元/件）" rules={[{ required: true }]}>
                        <Input placeholder="如 0.5000（商品可改）" />
                      </Form.Item>
                    </Col>
                    <Col span={8}>
                      <Form.Item name="sundriesDefault" label="日常杂费默认（元）" rules={[{ required: true }]}>
                        <Input placeholder="如 0.2000（商品只读使用）" />
                      </Form.Item>
                    </Col>
                    <Col span={8}>
                      <Form.Item name="rentUtilitiesDefault" label="房租水电默认（元）" rules={[{ required: true }]}>
                        <Input placeholder="如 0.4000（商品只读使用）" />
                      </Form.Item>
                    </Col>
                    <Col span={8}>
                      <Form.Item name="hourlyWage" label="时薪（元/小时）" rules={[{ required: true }]}>
                        <Input placeholder="如 15.0000（制品/包装/缝边人工费基数）" />
                      </Form.Item>
                    </Col>
                    <Col span={8}>
                      <Form.Item name="workdayHours" label="工作日小时数（小时/天）" rules={[{ required: true }]}>
                        <Input placeholder="如 8.0000（制品日薪 = 时薪 × 该值）" />
                      </Form.Item>
                    </Col>
                    <Col span={8}>
                      <Form.Item
                        name="makingEffectiveHourRate"
                        label="制品有效工时率（0–1）"
                        rules={[{ required: true }]}
                      >
                        <Input placeholder="如 0.750000（工作日小时数 × 该率 = 制品有效工时）" />
                      </Form.Item>
                    </Col>
                  </Row>
                  <Button type="primary" loading={saving} onClick={() => void saveValues()}>
                    保存单价与默认值
                  </Button>
                </Form>
              </>
            ),
          },
          {
            key: 'static',
            label: '静态数据',
            children: (
              <>
                <Typography.Paragraph type="secondary">
                  类别由系统固定，不可新增、删除或改名；类别下的条目按类别管理。被业务引用（商品或员工）的条目不允许删除；改名或改数值不影响既有快照。
                </Typography.Paragraph>
                <Table<StaticDataCategory>
                  size="small"
                  rowKey="code"
                  pagination={false}
                  dataSource={categories}
                  columns={[
                    {
                      title: '类别',
                      dataIndex: 'name',
                      render: (value: string) => (
                        <Space size={6}>
                          <span>{value}</span>
                          <Tag color="blue">系统内置</Tag>
                        </Space>
                      ),
                    },
                    { title: '系统标识', dataIndex: 'code', width: 180 },
                    { title: '条目数', dataIndex: 'itemCount', width: 100 },
                    {
                      title: '操作',
                      key: 'action',
                      width: 100,
                      render: (_: unknown, row: StaticDataCategory) => (
                        <Button type="link" size="small" onClick={() => void openCategory(row)}>
                          编辑
                        </Button>
                      ),
                    },
                  ]}
                />
              </>
            ),
          },
          {
            key: 'formulas',
            label: '公式说明',
            children: <FormulaCatalogPanel groups={formulaGroups} />,
          },
        ]}
      />

      <Modal
        open={active !== null}
        title={active ? `静态数据 · ${active.name}（${active.code}）` : ''}
        footer={null}
        width={720}
        onCancel={() => {
          setActive(null);
          setItems([]);
        }}
        destroyOnHidden
      >
        {isWorkType ? (
          <Typography.Paragraph type="secondary">
            员工工种为系统预置的四道工序，标识固定；只能改名称，不能新增、删除或停用。
          </Typography.Paragraph>
        ) : (
          <Button
            type="primary"
            style={{ marginBottom: 12 }}
            onClick={() => {
              setItemDraft(undefined);
              setItemModal({ open: true });
            }}
          >
            新建条目
          </Button>
        )}
        <Table<StaticDataItem> size="small" rowKey="id" pagination={false} dataSource={items} columns={itemColumns} />
      </Modal>

      <Modal
        open={itemModal.open}
        title={itemModal.id ? '编辑条目' : '新建条目'}
        footer={null}
        onCancel={() => setItemModal({ open: false })}
        destroyOnHidden
      >
        <Form form={itemForm} layout="vertical" onFinish={() => void saveItem()}>
          <Form.Item name="name" label="名称" rules={[{ required: true, whitespace: true, message: '请输入名称' }]}>
            <Input placeholder="类别内不可重名" />
          </Form.Item>
          {!isWorkType && (
            <Form.Item
              name="stdMinutes"
              label={active?.code === 'SEAM_TYPE' ? '缝边标准时长（分钟/件）' : '标准时长（分钟）'}
              rules={[{ required: true, message: '请输入时长' }]}
            >
              <Input placeholder="整数分钟，1-360" />
            </Form.Item>
          )}
          <Space>
            <Button type="primary" htmlType="submit" loading={saving}>
              保存
            </Button>
            <Button onClick={() => setItemModal({ open: false })}>取消</Button>
          </Space>
        </Form>
      </Modal>
    </Card>
  );
}

/** 公式说明：只读展示 calculation 模块发布的公式目录，按业务分组，不提供任何编辑入口。 */
function FormulaCatalogPanel({ groups }: { groups: FormulaGroup[] }) {
  if (groups.length === 0) {
    return <Typography.Paragraph type="secondary">暂无已实现的公式。</Typography.Paragraph>;
  }
  return (
    <Space orientation="vertical" size={16} style={{ width: '100%' }}>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
        公式由后端集中计算模块实现并发布，此页只读：不提供编辑、发布或生效时间设置，未建设的业务不显示空条目。
      </Typography.Paragraph>
      {groups.map(group => (
        <div key={group.category}>
          <Typography.Text strong>{group.category}</Typography.Text>
          <Table<FormulaEntry>
            style={{ marginTop: 8 }}
            size="small"
            rowKey="identifier"
            dataSource={group.formulas}
            pagination={false}
            columns={[
              { title: '标识', dataIndex: 'identifier', width: 110 },
              { title: '名称', dataIndex: 'name', width: 140 },
              { title: '输入与单位', dataIndex: 'inputs', width: 200 },
              { title: '表达式', dataIndex: 'expression' },
              { title: '舍入规则', dataIndex: 'rounding', width: 130 },
              { title: '结果含义', dataIndex: 'resultMeaning', width: 180 },
              { title: '固定示例', dataIndex: 'example', width: 220 },
            ]}
          />
        </div>
      ))}
    </Space>
  );
}
