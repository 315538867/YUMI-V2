import { useEffect, useMemo, useState } from 'react';
import { Alert, App, Button, Card, Col, Form, Input, InputNumber, Row, Space, Table, Tag, Typography } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import {
  getPlan,
  verifyPlan,
  PLAN_STATUS_LABELS,
  PLAN_TYPE_LABELS,
  type ProductionPlanView,
  type ProductionVerificationView,
} from '../../api/production';
import { NODE_LABELS } from '../../api/inventory';
import { describeApiError, YumiApiError } from '../../api/errors';
import { canSubmit, equationHolds, incompleteQuantity } from './verifyMath';

/**
 * 计划核验（任务 5.15）：显式操作页，把「计划 / 当前可执行 / 等待数量」与
 * 「本次完成 / 合格 / 返工 / 报废 / 未完成」分列清楚；错误码与字段错误直接展示。
 * 页面不做权威数量计算：未完成与等式只是即时提示，服务端在同一事务内重算上限并校验等式。
 */
export function ProductionVerifyPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const { id } = useParams();
  const [form] = Form.useForm();
  const [plan, setPlan] = useState<ProductionPlanView | null>(null);
  const [result, setResult] = useState<ProductionVerificationView | null>(null);
  const [fieldErrors, setFieldErrors] = useState<{ field: string; message: string }[]>([]);
  const [submitting, setSubmitting] = useState(false);

  const completed = Form.useWatch('completedQuantity', form) ?? 0;
  const qualified = Form.useWatch('qualifiedQuantity', form) ?? 0;
  const rework = Form.useWatch('reworkQuantity', form) ?? 0;
  const scrap = Form.useWatch('scrapQuantity', form) ?? 0;

  const inputs = { completedQuantity: completed, qualifiedQuantity: qualified, reworkQuantity: rework, scrapQuantity: scrap };
  const equationOk = equationHolds(inputs);
  const incomplete = useMemo(() => (plan ? incompleteQuantity(plan.quantity, inputs) : 0), [plan, completed, qualified, rework, scrap]);
  const submittable = plan ? canSubmit(plan.quantity, inputs) : false;

  useEffect(() => {
    (async () => {
      try {
        const loaded = await getPlan(id!);
        setPlan(loaded);
        form.setFieldsValue({
          completedQuantity: 0,
          qualifiedQuantity: 0,
          reworkQuantity: 0,
          scrapQuantity: 0,
        });
      } catch (error) {
        message.error(describeApiError(error));
      }
    })();
  }, [id]);

  async function submit() {
    const values = await form.validateFields();
    setSubmitting(true);
    setFieldErrors([]);
    try {
      const verification = await verifyPlan(id!, values);
      setResult(verification);
      message.success('核验已保存');
      setPlan(await getPlan(id!));
    } catch (error) {
      if (error instanceof YumiApiError) {
        setFieldErrors(error.fieldErrors);
        message.error(describeApiError(error));
      } else {
        message.error(describeApiError(error));
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Row gutter={24}>
      <Col span={14}>
        <Card
          title={
            <Space>
              <Typography.Text strong>计划核验</Typography.Text>
              {plan && <Tag>{plan.planNo}</Tag>}
            </Space>
          }
          extra={<Button onClick={() => navigate('/production')}>返回工作台</Button>}
        >
          {plan && (
            <>
              <Table<ProductionPlanView>
                size="small"
                rowKey="id"
                pagination={false}
                showHeader={false}
                dataSource={[plan]}
                columns={[
                  { title: '项目', dataIndex: 'planNo', width: 140, render: () => '计划信息' },
                  {
                    title: '值',
                    render: (_, row) => (
                      <Space size={12} wrap>
                        <span>
                          #{row.lineNo} {row.orderNo ?? ''} {row.productName ?? ''}
                        </span>
                        <span>工序 {NODE_LABELS[row.node]}</span>
                        <span>类型 {PLAN_TYPE_LABELS[row.planType]}</span>
                        <span>日期 {row.planDate}</span>
                        <span>员工 {row.employeeName}</span>
                        <Tag>{PLAN_STATUS_LABELS[row.status]}</Tag>
                        {row.status === 'PENDING' && (row.waitingUpstream ? <Tag>等待上游</Tag> : <Tag color="cyan">可执行</Tag>)}
                      </Space>
                    ),
                  },
                ]}
              />
              <Row gutter={16} style={{ marginTop: 16 }}>
                <Col span={8}>
                  <Typography.Text type="secondary">计划数量</Typography.Text>
                  <Typography.Title level={4} style={{ margin: 0 }}>
                    {plan.quantity}
                  </Typography.Title>
                </Col>
                <Col span={8}>
                  <Typography.Text type="secondary">当前可执行</Typography.Text>
                  <Typography.Title level={4} style={{ margin: 0 }}>
                    {plan.executableQuantity}
                  </Typography.Title>
                </Col>
                <Col span={8}>
                  <Typography.Text type="secondary">该工序待安排</Typography.Text>
                  <Typography.Title level={4} style={{ margin: 0 }}>
                    {plan.schedulableQuantity}
                  </Typography.Title>
                </Col>
              </Row>
              <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
                已核验处理 {plan.nodeVerified} · 有效流入 {plan.nodeInflow}；本次完成不得超过核验时重算的当前可执行数量。
              </Typography.Paragraph>
            </>
          )}

          {plan?.status === 'PENDING' && !result && (
            <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
              <Row gutter={16}>
                <Col span={6}>
                  <Form.Item name="completedQuantity" label="本次完成" rules={[{ required: true, message: '请填写本次完成' }]}>
                    <InputNumber min={0} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                </Col>
                <Col span={6}>
                  <Form.Item name="qualifiedQuantity" label="合格" rules={[{ required: true, message: '请填写合格数量' }]}>
                    <InputNumber min={0} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                </Col>
                <Col span={6}>
                  <Form.Item name="reworkQuantity" label="返工" rules={[{ required: true, message: '请填写返工数量' }]}>
                    <InputNumber min={0} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                </Col>
                <Col span={6}>
                  <Form.Item name="scrapQuantity" label="报废" rules={[{ required: true, message: '请填写报废数量' }]}>
                    <InputNumber min={0} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item name="verifyNote" label="核验备注（可空）">
                <Input />
              </Form.Item>
              <Space size={12} wrap>
                <Tag color={equationOk ? 'green' : 'red'}>
                  本次完成 {completed} = 合格 {qualified} + 返工 {rework} + 报废 {scrap}
                  {equationOk ? '' : '（等式不成立）'}
                </Tag>
                <Tag color={incomplete < 0 ? 'red' : 'default'}>未完成 {incomplete}</Tag>
              </Space>
              {fieldErrors.length > 0 && (
                <Alert
                  style={{ marginTop: 12 }}
                  type="error"
                  showIcon
                  title="核验未通过，服务端字段错误"
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
              <Space style={{ marginTop: 16 }}>
                <Button type="primary" loading={submitting} disabled={!submittable} onClick={() => void submit()}>
                  提交核验
                </Button>
                <Button onClick={() => navigate('/production')}>返回工作台</Button>
              </Space>
            </Form>
          )}

          {plan && plan.status !== 'PENDING' && !result && (
            <Alert
              style={{ marginTop: 16 }}
              type="warning"
              showIcon
              title={`该计划为${PLAN_STATUS_LABELS[plan.status]}，不能核验`}
              description="每计划最多一次有效核验；已核验事实不可修改，需要变更请通过返工/重做来源或更正流程处理。"
            />
          )}
        </Card>
      </Col>
      <Col span={10}>
        <Card title={<Space>核验结果<Tag color="blue">服务端权威</Tag></Space>}>
          {result ? (
            <>
              <Alert
                type="success"
                showIcon
                title={`核验已保存：完成 ${result.completedQuantity}，未完成 ${result.incompleteQuantity}`}
              />
              <table className="preview-table" style={{ marginTop: 12 }}>
                <tbody>
                  <tr><td>合格</td><td>{result.qualifiedQuantity}</td></tr>
                  <tr><td>返工</td><td>{result.reworkQuantity}</td></tr>
                  <tr><td>报废</td><td>{result.scrapQuantity}</td></tr>
                  <tr><td>未完成</td><td><b>{result.incompleteQuantity}</b></td></tr>
                </tbody>
              </table>
              <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 4 }}>
                合格分流去向
              </Typography.Paragraph>
              {result.flows.length === 0 ? (
                <Typography.Text type="secondary">本次没有合格数量</Typography.Text>
              ) : (
                <Space size={8} wrap>
                  {result.flows.map(flow => (
                    <Tag key={flow.node} color="cyan">
                      {NODE_LABELS[flow.node as keyof typeof NODE_LABELS] ?? flow.node} {flow.quantity}
                    </Tag>
                  ))}
                </Space>
              )}
              <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
                返工/报废只进入待安排额度（不自动创建计划、不增加订单需求），来源与未完成提醒可在工作台对应页签处理。
              </Typography.Paragraph>
            </>
          ) : (
            <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
              提交后显示服务端返回的核验事实与合格分流去向；页面不做权威数量计算。
            </Typography.Paragraph>
          )}
        </Card>
      </Col>
    </Row>
  );
}
