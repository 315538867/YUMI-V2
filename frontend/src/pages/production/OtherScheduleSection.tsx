import { useState } from 'react';
import { Alert, App, Button, Card, Form, Input, InputNumber, Modal, Space, Table, Typography } from 'antd';
import {
  cancelOtherSchedule,
  correctOtherSchedule,
  OTHER_SCHEDULE_STATUS_LABELS,
  verifyOtherSchedule,
  type OtherScheduleView,
} from '../../api/production';
import { describeApiError, YumiApiError } from '../../api/errors';

type ModalState =
  | { kind: 'verify'; schedule: OtherScheduleView }
  | { kind: 'cancel'; schedule: OtherScheduleView }
  | { kind: 'correct'; schedule: OtherScheduleView }
  | null;

export interface OtherScheduleSectionProps {
  schedules: OtherScheduleView[];
  loading?: boolean;
  onChanged: () => void;
}

/**
 * 其他排班（沿用既有 UI，不改造）：只保存总分钟与工时事实，不产生商品/库存/订单履约数量；
 * 新建入口在统一「新建排班」表单内选择「其他排班」，这里保留核验、取消与更正。
 */
export function OtherScheduleSection({ schedules, loading, onChanged }: OtherScheduleSectionProps) {
  const { message } = App.useApp();
  const [form] = Form.useForm();
  const [modal, setModal] = useState<ModalState>(null);
  const [submitting, setSubmitting] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<{ field: string; message: string }[]>([]);

  function open(next: Exclude<ModalState, null>, initial: Record<string, unknown>) {
    form.resetFields();
    form.setFieldsValue(initial);
    setFieldErrors([]);
    setModal(next);
  }

  async function submit() {
    if (!modal) {
      return;
    }
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (modal.kind === 'verify') {
        await verifyOtherSchedule(modal.schedule.id, {
          hours: values.hours,
          minutes: values.minutes,
          note: values.note,
        });
      } else if (modal.kind === 'cancel') {
        await cancelOtherSchedule(modal.schedule.id, values.reason);
      } else {
        await correctOtherSchedule(modal.schedule.id, {
          hours: values.hours,
          minutes: values.minutes,
          reason: values.reason,
        });
      }
      message.success('操作已完成');
      setModal(null);
      onChanged();
    } catch (error) {
      if (error instanceof YumiApiError) {
        setFieldErrors(error.fieldErrors);
      }
      message.error(describeApiError(error));
    } finally {
      setSubmitting(false);
    }
  }

  const title =
    modal?.kind === 'verify'
      ? `工时核验 ${modal.schedule.scheduleNo}`
      : modal?.kind === 'cancel'
        ? `取消排班 ${modal.schedule.scheduleNo}`
        : modal?.kind === 'correct'
          ? `工时更正 ${modal.schedule.scheduleNo}`
          : '';

  return (
    <Card
      size="small"
      title={
        <Space>
          <Typography.Text strong>其他排班（工时事实）</Typography.Text>
          <Typography.Text type="secondary">只保存总分钟，不产生商品数量</Typography.Text>
        </Space>
      }
    >
      <Table<OtherScheduleView>
        size="small"
        rowKey="id"
        loading={loading}
        dataSource={schedules}
        pagination={false}
        locale={{ emptyText: '暂无其他排班；从「新建排班」选择其他排班创建' }}
        columns={[
          { title: '排班编号', dataIndex: 'scheduleNo', width: 110 },
          { title: '日期', dataIndex: 'scheduleDate', width: 110 },
          { title: '员工', dataIndex: 'employeeName', width: 140, ellipsis: true },
          {
            title: '计划工时',
            key: 'planned',
            width: 160,
            render: (_: unknown, row: OtherScheduleView) =>
              `${row.hours} 时 ${row.minutes} 分（${row.totalMinutes} 分钟）`,
          },
          {
            title: '有效工时',
            key: 'effective',
            width: 150,
            render: (_: unknown, row: OtherScheduleView) =>
              row.effectiveMinutes == null
                ? '未核验'
                : `${row.effectiveMinutes} 分钟${row.corrected ? '（已更正）' : ''}`,
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 90,
            render: (value: OtherScheduleView['status']) => OTHER_SCHEDULE_STATUS_LABELS[value],
          },
          {
            title: '操作',
            key: 'action',
            width: 200,
            render: (_: unknown, row: OtherScheduleView) => (
              <Space size={4}>
                {row.status === 'PENDING' && (
                  <>
                    <Button
                      type="link"
                      size="small"
                      onClick={() => open({ kind: 'verify', schedule: row }, { hours: row.hours, minutes: row.minutes })}
                    >
                      核验
                    </Button>
                    <Button type="link" size="small" danger onClick={() => open({ kind: 'cancel', schedule: row }, { reason: undefined })}>
                      取消
                    </Button>
                  </>
                )}
                {row.status === 'VERIFIED' && (
                  <Button
                    type="link"
                    size="small"
                    onClick={() =>
                      open({ kind: 'correct', schedule: row }, { hours: row.hours, minutes: row.minutes, reason: undefined })
                    }
                  >
                    更正工时
                  </Button>
                )}
              </Space>
            ),
          },
        ]}
      />

      <Modal
        open={modal !== null}
        title={title}
        onCancel={() => setModal(null)}
        onOk={() => void submit()}
        confirmLoading={submitting}
        okText="提交"
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          title={
            modal?.kind === 'verify'
              ? '只能核验一次；核验后录错请使用「更正工时」。'
              : modal?.kind === 'cancel'
                ? '只有待执行的排班可以取消。'
                : '更正只追加事实，原核验不变；有效工时取最新更正。'
          }
        />
        {fieldErrors.length > 0 && (
          <Alert
            type="error"
            showIcon
            style={{ marginBottom: 12 }}
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
        <Form form={form} layout="vertical">
          {(modal?.kind === 'verify' || modal?.kind === 'correct') && (
            <>
              <Form.Item name="hours" label="实际小时" rules={[{ required: true, message: '请填写小时' }]}>
                <InputNumber min={0} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="minutes" label="实际分钟（0–59）" rules={[{ required: true, message: '请填写分钟' }]}>
                <InputNumber min={0} max={59} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              {modal.kind === 'verify' ? (
                <Form.Item name="note" label="核验备注（可空）">
                  <Input />
                </Form.Item>
              ) : (
                <Form.Item name="reason" label="更正原因" rules={[{ required: true, message: '请填写原因' }]}>
                  <Input />
                </Form.Item>
              )}
            </>
          )}
          {modal?.kind === 'cancel' && (
            <Form.Item name="reason" label="取消原因" rules={[{ required: true, message: '请填写取消原因' }]}>
              <Input />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </Card>
  );
}
