import { useEffect, useState } from 'react';
import {
  App,
  Button,
  Card,
  DatePicker,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import {
  WORK_TYPE_OPTIONS,
  createEmployee,
  getEmployee,
  leaveEmployee,
  listEmployees,
  rehireEmployee,
  updateEmployee,
  type EmployeeView,
} from '../../api/catalog';
import { describeApiError } from '../../api/errors';

type Mode = { kind: 'list' } | { kind: 'create' } | { kind: 'edit'; id: number };

export function EmployeesPage() {
  const { message } = App.useApp();
  const [mode, setMode] = useState<Mode>({ kind: 'list' });
  const [rows, setRows] = useState<EmployeeView[]>([]);
  const [loading, setLoading] = useState(false);
  const [statusFilter, setStatusFilter] = useState<string | undefined>('ACTIVE');
  const [nameFilter, setNameFilter] = useState('');
  const [leaveTarget, setLeaveTarget] = useState<EmployeeView | null>(null);
  const [rehireTarget, setRehireTarget] = useState<EmployeeView | null>(null);

  async function reload(status?: string, name?: string) {
    setLoading(true);
    try {
      setRows(await listEmployees({ status, name: name || undefined }));
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void reload(statusFilter);
  }, []);

  async function onLeave(values: { reason: string; date: Dayjs }) {
    if (!leaveTarget) return;
    try {
      await leaveEmployee(leaveTarget.id!, { reason: values.reason, date: values.date.format('YYYY-MM-DD') });
      message.success(`${leaveTarget.name} 已离职`);
      setLeaveTarget(null);
      void reload(statusFilter, nameFilter);
    } catch (error) {
      message.error(describeApiError(error));
    }
  }

  async function onRehire(values: { date: Dayjs }) {
    if (!rehireTarget) return;
    try {
      await rehireEmployee(rehireTarget.id!, { date: values.date.format('YYYY-MM-DD') });
      message.success(`${rehireTarget.name} 已重新入职`);
      setRehireTarget(null);
      void reload(statusFilter, nameFilter);
    } catch (error) {
      message.error(describeApiError(error));
    }
  }

  if (mode.kind !== 'list') {
    return (
      <EmployeeEditor
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
        员工
      </Typography.Title>
      <Space.Compact style={{ marginBottom: 12 }}>
        <Select
          placeholder="任职状态"
          style={{ width: 130 }}
          value={statusFilter}
          onChange={value => setStatusFilter(value)}
          options={[
            { value: 'ACTIVE', label: '在职' },
            { value: 'LEFT', label: '离职' },
          ]}
        />
        <Input
          placeholder="姓名"
          value={nameFilter}
          onChange={e => setNameFilter(e.target.value)}
          onPressEnter={() => void reload(statusFilter, nameFilter)}
          allowClear
        />
        <Button onClick={() => void reload(statusFilter, nameFilter)}>查询</Button>
      </Space.Compact>
      <Button type="primary" style={{ marginLeft: 8 }} onClick={() => setMode({ kind: 'create' })}>
        新建员工
      </Button>
      <Table<EmployeeView>
        style={{ marginTop: 12 }}
        size="small"
        rowKey="employeeNo"
        loading={loading}
        dataSource={rows}
        pagination={false}
        columns={[
          { title: '员工编号', dataIndex: 'employeeNo', width: 100 },
          { title: '姓名', dataIndex: 'name', width: 110 },
          { title: '电话', dataIndex: 'phone', width: 130 },
          { title: '首次入职', dataIndex: 'firstHireDate', width: 110 },
          {
            title: '工作类型',
            key: 'workTypes',
            render: (_, row) => row.workTypes.map(t => <Tag key={t.code}>{t.name}</Tag>),
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 80,
            render: status => (
              <Tag color={status === 'ACTIVE' ? 'green' : 'default'}>{status === 'ACTIVE' ? '在职' : '离职'}</Tag>
            ),
          },
          {
            title: '操作',
            key: 'action',
            width: 190,
            render: (_, row) => (
              <Space size={4}>
                <Button type="link" size="small" onClick={() => setMode({ kind: 'edit', id: row.id ?? 0 })}>
                  编辑
                </Button>
                {row.status === 'ACTIVE' ? (
                  <Button type="link" size="small" danger onClick={() => setLeaveTarget(row)}>
                    离职
                  </Button>
                ) : (
                  <Button type="link" size="small" onClick={() => setRehireTarget(row)}>
                    重新入职
                  </Button>
                )}
              </Space>
            ),
          },
        ]}
      />

      <Modal
        open={leaveTarget !== null}
        title={leaveTarget ? `办理离职：${leaveTarget.name}（${leaveTarget.employeeNo}）` : ''}
        footer={null}
        onCancel={() => setLeaveTarget(null)}
        destroyOnHidden
      >
        <LeaveForm onSubmit={onLeave} onCancel={() => setLeaveTarget(null)} />
      </Modal>
      <Modal
        open={rehireTarget !== null}
        title={rehireTarget ? `重新入职：${rehireTarget.name}（${rehireTarget.employeeNo}）` : ''}
        footer={null}
        onCancel={() => setRehireTarget(null)}
        destroyOnHidden
      >
        <RehireForm onSubmit={onRehire} onCancel={() => setRehireTarget(null)} />
      </Modal>
    </Card>
  );
}

function LeaveForm({
  onSubmit,
  onCancel,
}: {
  onSubmit: (values: { reason: string; date: Dayjs }) => void;
  onCancel: () => void;
}) {
  const [form] = Form.useForm();
  return (
    <Form
      form={form}
      layout="vertical"
      initialValues={{ date: dayjs() }}
      onFinish={values => onSubmit(values)}
    >
      <Form.Item name="date" label="离职日期" rules={[{ required: true }]}>
        <DatePicker style={{ width: '100%' }} />
      </Form.Item>
      <Form.Item name="reason" label="离职原因" rules={[{ required: true, whitespace: true, message: '请填写离职原因' }]}>
        <Input.TextArea rows={2} />
      </Form.Item>
      <Space>
        <Button type="primary" danger htmlType="submit">
          确认离职
        </Button>
        <Button onClick={onCancel}>取消</Button>
      </Space>
    </Form>
  );
}

function RehireForm({
  onSubmit,
  onCancel,
}: {
  onSubmit: (values: { date: Dayjs }) => void;
  onCancel: () => void;
}) {
  const [form] = Form.useForm();
  return (
    <Form form={form} layout="vertical" initialValues={{ date: dayjs() }} onFinish={values => onSubmit(values)}>
      <Form.Item name="date" label="重新入职日期" rules={[{ required: true }]}>
        <DatePicker style={{ width: '100%' }} />
      </Form.Item>
      <Space>
        <Button type="primary" htmlType="submit">
          确认重新入职
        </Button>
        <Button onClick={onCancel}>取消</Button>
      </Space>
    </Form>
  );
}

function EmployeeEditor({ mode, onDone }: { mode: Exclude<Mode, { kind: 'list' }>; onDone: () => void }) {
  const { message } = App.useApp();
  const [form] = Form.useForm();
  const [detail, setDetail] = useState<EmployeeView | null>(null);
  const [saving, setSaving] = useState(false);
  const editing = mode.kind === 'edit';

  useEffect(() => {
    if (editing) {
      getEmployee(mode.id)
        .then(data => {
          setDetail(data);
          form.setFieldsValue(data);
        })
        .catch(error => message.error(describeApiError(error)));
    }
  }, [editing, mode]);

  async function submit() {
    const values = await form.validateFields();
    setSaving(true);
    try {
      if (editing) {
        await updateEmployee(mode.id, { ...values, version: detail!.version });
        message.success('员工资料已保存');
      } else {
        await createEmployee(values);
        message.success('员工已创建');
      }
      onDone();
    } catch (error) {
      message.error(describeApiError(error));
    } finally {
      setSaving(false);
    }
  }

  return (
    <Card>
      <Typography.Title level={4} style={{ marginTop: 0 }}>
        {editing ? `编辑员工 ${detail?.employeeNo ?? ''}` : '新建员工'}
      </Typography.Title>
      <Form form={form} layout="vertical" style={{ maxWidth: 640 }}>
        <Form.Item name="name" label="姓名" rules={[{ required: true, whitespace: true, message: '请输入姓名' }]}>
          <Input />
        </Form.Item>
        <Form.Item name="phone" label="电话">
          <Input />
        </Form.Item>
        <Form.Item
          name="firstHireDate"
          label="首次入职日期"
          rules={[{ required: true, message: '请选择首次入职日期' }]}
        >
          <Input disabled={editing} placeholder="YYYY-MM-DD" style={{ width: 220 }} />
        </Form.Item>
        <Form.Item
          name="workTypes"
          label="工作类型"
          rules={[{ required: true, message: '至少选择一个工作类型' }]}
        >
          <Select mode="multiple" options={WORK_TYPE_OPTIONS.map(t => ({ value: t.code, label: t.name }))} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="note" label="备注">
          <Input.TextArea rows={2} />
        </Form.Item>
        <Space>
          <Button type="primary" loading={saving} onClick={() => void submit()}>
            {editing ? '保存修改' : '创建员工'}
          </Button>
          <Button onClick={onDone}>返回列表</Button>
        </Space>
      </Form>
    </Card>
  );
}
