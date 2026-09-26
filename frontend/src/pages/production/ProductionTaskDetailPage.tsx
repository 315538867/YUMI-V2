import { useEffect, useMemo, useState } from 'react';
import { Alert, App, Badge, Button, Card, Descriptions, Space, Tag, Typography } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import {
  DERIVED_STATUS_COLORS,
  DERIVED_STATUS_LABELS,
  FACT_TYPE_LABELS,
  ITEM_STATUS_LABELS,
  SOURCE_TYPE_LABELS,
  TASK_TYPE_LABELS,
  getProductionTask,
  getProductionTaskFacts,
  type ProductionFact,
  type ProductionTaskView,
} from '../../api/production';
import { NODE_LABELS } from '../../api/inventory';
import { describeApiError } from '../../api/errors';
import { ProductionFactTimeline } from '../../components/production/ProductionFactTimeline';
import { ProductionItemFactTable } from '../../components/production/ProductionItemFactTable';
import { employeeBadgeColor } from './productionLogic';

export interface ProductionTaskDetailPageProps {
  /** 测试注入：给定后不再发起初始加载。 */
  initialTask?: ProductionTaskView;
  /** 测试注入：给定后不再请求 `/facts`。 */
  initialFacts?: ProductionFact[];
}

function nodeLabel(node?: string): string {
  if (!node) {
    return '—';
  }
  return NODE_LABELS[node as keyof typeof NODE_LABELS] ?? node;
}

/**
 * 任务详情（阶段五 5.18，只读，对齐原型 `.back` / `.metrics` / `.section-card` / `.follow`）：
 * 返回链接 + 任务头 + 指标条 + 明细快照（含逐明细核验分解）+ 单一只读 `/facts` 端点时间线 +
 * 后续操作块；**不预置任何编辑表单**，也不在本地派生权威数量（指标只统计记录条数）。
 */
export function ProductionTaskDetailPage({
  initialTask,
  initialFacts,
}: ProductionTaskDetailPageProps = {}) {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const { id } = useParams();
  const [task, setTask] = useState<ProductionTaskView | null>(initialTask ?? null);
  const [facts, setFacts] = useState<ProductionFact[]>(initialFacts ?? []);

  useEffect(() => {
    if (initialTask) {
      return;
    }
    (async () => {
      try {
        const loaded = await getProductionTask(id!);
        setTask(loaded);
        setFacts(await getProductionTaskFacts(loaded.id));
      } catch (error) {
        message.error(describeApiError(error));
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, initialTask]);

  const hasPending = task?.items.some(item => item.status === 'PENDING') ?? false;

  const factTypes = useMemo(() => [...new Set(facts.map(fact => fact.factType))], [facts]);

  const pendingCount = task?.items.filter(item => item.status === 'PENDING').length ?? 0;
  const verifiedCount = task?.items.filter(item => item.status === 'VERIFIED').length ?? 0;
  const cancelledCount = task?.items.filter(item => item.status === 'CANCELLED').length ?? 0;

  return (
    <div className="production-page">
      <Button type="link" className="back" onClick={() => navigate('/production')}>
        ← 返回工作台
      </Button>

      <div className="page-heading">
        <div>
          <Typography.Title level={4} style={{ margin: '0 0 6px' }}>
            生产任务详情
            <span className="heading-no">{task?.taskNo ?? ''}</span>
            {task && (
              <Tag className="heading-type">
                {TASK_TYPE_LABELS[task.taskType] ?? task.taskType}
              </Tag>
            )}
          </Typography.Title>
          <Space size={8} wrap>
            <Tag color="default">只读</Tag>
            {task && (
              <Tag color={DERIVED_STATUS_COLORS[task.derivedStatus] ?? 'default'}>
                {DERIVED_STATUS_LABELS[task.derivedStatus] ?? task.derivedStatus}
              </Tag>
            )}
            <Typography.Text type="secondary">查看与操作分离，本页为只读视图</Typography.Text>
          </Space>
        </div>
      </div>

      {!task ? (
        <Alert type="info" showIcon title="加载中或任务不存在" />
      ) : (
        <>
          <Card className="section-card">
            <div
              className="metrics"
              data-testid="task-metrics"
              style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)' }}
            >
              <div>
                <span>明细数</span>
                <strong>{task.items.length}</strong>
                <small>条</small>
              </div>
              <div>
                <span>待执行明细</span>
                <strong>{pendingCount}</strong>
                <small>条</small>
              </div>
              <div>
                <span>已核验明细</span>
                <strong>{verifiedCount}</strong>
                <small>条</small>
              </div>
              <div>
                <span>事实条数</span>
                <strong>{facts.length}</strong>
                <small>条</small>
              </div>
            </div>
            <Descriptions column={3} size="small">
              <Descriptions.Item label="任务日期">{task.taskDate}</Descriptions.Item>
              <Descriptions.Item label="工序">{task.workTypeName || nodeLabel(task.items[0]?.node)}</Descriptions.Item>
              <Descriptions.Item label="员工">
                <Badge color={employeeBadgeColor(task.employeeId)} text={task.employeeName} />
              </Descriptions.Item>
              <Descriptions.Item label="已取消明细">{cancelledCount}</Descriptions.Item>
              <Descriptions.Item label="备注">{task.note ?? '—'}</Descriptions.Item>
            </Descriptions>
          </Card>

          <Card
            className="section-card"
            size="small"
            title="明细与数量快照（计划 / 实际流入 / 当前可执行 / 已核验处理 / 核验分解）"
          >
            <ProductionItemFactTable items={task.items} />
          </Card>

          <Card
            className="section-card"
            size="small"
            title={`事实追溯时间线（单一只读端点，按 factTime ASC, factType ASC, factId ASC；共 ${facts.length} 条）`}
          >
            <Space size={6} wrap style={{ marginBottom: 8 }}>
              {factTypes.map(type => (
                <Tag key={type} color="blue">
                  {FACT_TYPE_LABELS[type] ?? type}
                </Tag>
              ))}
              {task.items[0] && (
                <>
                  <Tag>{SOURCE_TYPE_LABELS[task.items[0].sourceType] ?? task.items[0].sourceType}</Tag>
                  <Tag>{ITEM_STATUS_LABELS[task.items[0].status]}</Tag>
                </>
              )}
            </Space>
            <ProductionFactTimeline facts={facts} />
          </Card>

          <div className="follow" data-testid="task-follow">
            <Typography.Text strong>后续操作</Typography.Text>
            <div className="follow-row">
              <Typography.Text type="secondary">
                待执行明细需一次提交整个任务，失败整批回滚且不产生任何事实。
              </Typography.Text>
              <Button
                type="primary"
                disabled={!hasPending}
                onClick={() => navigate(`/production/tasks/${task.id}/verify`)}
              >
                批量核验
              </Button>
            </div>
            <div className="follow-row">
              <Typography.Text type="secondary">
                返回工作台只发只读请求，取消/返回不写；任务已完成则无需再核验。
              </Typography.Text>
              <Button onClick={() => navigate('/production')}>返回工作台</Button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
