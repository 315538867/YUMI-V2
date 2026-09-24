import { useEffect, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Space,
  Table,
  Typography,
} from 'antd';
import {
  createCustomer,
  getCustomer,
  listCustomers,
  updateCustomer,
  type CustomerDetail,
  type CustomerView,
  type DuplicateCandidate,
} from '../../api/catalog';
import { describeApiError, YumiApiError } from '../../api/errors';

type Mode = { kind: 'list' } | { kind: 'create' } | { kind: 'edit'; id: number };

export function CustomersPage() {
  const { message } = App.useApp();
  const [mode, setMode] = useState<Mode>({ kind: 'list' });
  const [rows, setRows] = useState<CustomerView[]>([]);
  const [loading, setLoading] = useState(false);
  const [nameFilter, setNameFilter] = useState('');
  const [phoneFilter, setPhoneFilter] = useState('');

  async function reload(name?: string, phone?: string) {
    setLoading(true);
    try {
      setRows(await listCustomers({ name: name || undefined, phone: phone || undefined }));
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void reload();
  }, []);

  if (mode.kind !== 'list') {
    return (
      <CustomerEditor
        mode={mode}
        onDone={() => {
          setMode({ kind: 'list' });
          void reload(nameFilter, phoneFilter);
        }}
      />
    );
  }

  return (
    <Card>
      <Typography.Title level={4} style={{ marginTop: 0 }}>
        客户
      </Typography.Title>
      <Space.Compact style={{ marginBottom: 12 }}>
        <Input
          placeholder="客户名称"
          value={nameFilter}
          onChange={e => setNameFilter(e.target.value)}
          onPressEnter={() => void reload(nameFilter, phoneFilter)}
          allowClear
        />
        <Input
          placeholder="联系电话"
          value={phoneFilter}
          onChange={e => setPhoneFilter(e.target.value)}
          onPressEnter={() => void reload(nameFilter, phoneFilter)}
          allowClear
        />
        <Button onClick={() => void reload(nameFilter, phoneFilter)}>查询</Button>
      </Space.Compact>
      <Button type="primary" style={{ marginLeft: 8 }} onClick={() => setMode({ kind: 'create' })}>
        新建客户
      </Button>
      <Table<CustomerView>
        style={{ marginTop: 12 }}
        size="small"
        rowKey="customerNo"
        loading={loading}
        dataSource={rows}
        pagination={false}
        columns={[
          { title: '客户编号', dataIndex: 'customerNo', width: 100 },
          { title: '名称', dataIndex: 'name' },
          { title: '联系人', dataIndex: 'contact', width: 110 },
          { title: '电话', dataIndex: 'phone', width: 130 },
          {
            title: '默认收货',
            key: 'ship',
            render: (_, row) =>
              `${row.defaultRecipient} ${row.defaultRecipientPhone} ${row.defaultRegion} ${row.defaultAddress}`,
            ellipsis: true,
          },
          {
            title: '操作',
            key: 'action',
            width: 90,
            render: (_, row) => (
              <Button type="link" size="small" onClick={() => setMode({ kind: 'edit', id: row.id ?? 0 })}>
                编辑
              </Button>
            ),
          },
        ]}
      />
    </Card>
  );
}

function CustomerEditor({ mode, onDone }: { mode: Exclude<Mode, { kind: 'list' }>; onDone: () => void }) {
  const { message } = App.useApp();
  const [form] = Form.useForm();
  const [detail, setDetail] = useState<CustomerDetail | null>(null);
  const [duplicates, setDuplicates] = useState<DuplicateCandidate[]>([]);
  const [saving, setSaving] = useState(false);
  const editing = mode.kind === 'edit';

  useEffect(() => {
    if (editing) {
      getCustomer(mode.id)
        .then(data => {
          setDetail(data);
          form.setFieldsValue(data);
        })
        .catch(error => message.error(describeApiError(error)));
    }
  }, [editing, mode]);

  async function submit(confirmed: boolean) {
    const values = await form.validateFields();
    setSaving(true);
    try {
      if (editing) {
        await updateCustomer(mode.id, { ...values, version: detail!.version });
        message.success('客户已保存');
        onDone();
        return;
      }
      const result = await createCustomer({ ...values, duplicateConfirmed: confirmed });
      if ('created' in result && result.created === false) {
        setDuplicates(result.duplicateCandidates ?? []);
        return;
      }
      message.success('客户已创建');
      onDone();
    } catch (error) {
      if (error instanceof YumiApiError && duplicates.length > 0) setDuplicates([]);
      message.error(describeApiError(error));
    } finally {
      setSaving(false);
    }
  }

  return (
    <Card>
      <Typography.Title level={4} style={{ marginTop: 0 }}>
        {editing ? `编辑客户 ${detail?.customerNo ?? ''}` : '新建客户'}
      </Typography.Title>
      {duplicates.length > 0 && !editing && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="可能存在重复客户（系统不会自动合并）"
          description={
            <ul style={{ margin: 0, paddingLeft: 20 }}>
              {duplicates.map(c => (
                <li key={c.customerNo}>
                  {c.customerNo}　{c.name}　{c.phone ?? '—'}
                </li>
              ))}
            </ul>
          }
          action={
            <Space>
              <Button size="small" onClick={() => setDuplicates([])}>
                返回修改
              </Button>
              <Button size="small" type="primary" loading={saving} onClick={() => void submit(true)}>
                确认是不同客户，继续创建
              </Button>
            </Space>
          }
        />
      )}
      {editing && detail && (
        <Descriptions size="small" column={4} style={{ marginBottom: 16 }}>
          <Descriptions.Item label="订单数">{detail.summary.orderCount}</Descriptions.Item>
          <Descriptions.Item label="累计订购">{detail.summary.totalOrdered}</Descriptions.Item>
          <Descriptions.Item label="累计收款">{detail.summary.totalReceived}</Descriptions.Item>
          <Descriptions.Item label="累计退款">{detail.summary.totalRefunded}</Descriptions.Item>
        </Descriptions>
      )}
      <Form form={form} layout="vertical" style={{ maxWidth: 720 }}>
        <Typography.Text strong>基础资料</Typography.Text>
        <Form.Item name="name" label="客户名称" rules={[{ required: true, whitespace: true, message: '请输入客户名称' }]}>
          <Input />
        </Form.Item>
        <Form.Item name="contact" label="联系人">
          <Input />
        </Form.Item>
        <Form.Item name="phone" label="联系电话">
          <Input />
        </Form.Item>
        <Form.Item name="note" label="备注">
          <Input.TextArea rows={2} />
        </Form.Item>
        <Typography.Text strong>默认收货信息</Typography.Text>
        <Form.Item name="defaultRecipient" label="收货人" rules={[{ required: true, whitespace: true, message: '请输入收货人' }]}>
          <Input />
        </Form.Item>
        <Form.Item name="defaultRecipientPhone" label="收货电话" rules={[{ required: true, whitespace: true, message: '请输入收货电话' }]}>
          <Input />
        </Form.Item>
        <Form.Item name="defaultRegion" label="地区" rules={[{ required: true, whitespace: true, message: '请输入地区' }]}>
          <Input />
        </Form.Item>
        <Form.Item name="defaultAddress" label="详细地址" rules={[{ required: true, whitespace: true, message: '请输入详细地址' }]}>
          <Input />
        </Form.Item>
        {editing && (
          <Form.Item name="reason" label="修改原因（可选）">
            <Input />
          </Form.Item>
        )}
        <Space>
          <Button type="primary" loading={saving} onClick={() => void submit(false)}>
            {editing ? '保存修改' : '创建客户'}
          </Button>
          <Button onClick={onDone}>返回列表</Button>
        </Space>
      </Form>
    </Card>
  );
}
