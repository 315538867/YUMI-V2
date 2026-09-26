import { useEffect, useMemo, useState } from 'react';
import { Alert, App, Button, Card, Input, InputNumber, Space, Table, Tag, Typography } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import {
  getProductionTask,
  ITEM_STATUS_LABELS,
  verifyProductionTask,
  type ProductionTaskItemView,
  type ProductionTaskView,
  type ProductionVerificationView,
} from '../../api/production';
import { NODE_LABELS } from '../../api/inventory';
import { describeApiError, YumiApiError, type ApiFieldError } from '../../api/errors';
import {
  completedQuantity,
  describeVerifyFailure,
  mapFieldErrorsToItems,
  verifyItemBlocker,
  verifySubmitItems,
  type VerifyInput,
} from './productionLogic';

export interface VerifyItemInputsProps {
  item: ProductionTaskItemView;
  input: VerifyInput;
  note?: string;
  errors?: { field: string; message: string }[];
  onChange: (patch: VerifyInput) => void;
  onNoteChange?: (note: string) => void;
}

/**
 * 单条明细核验输入（阶段五 5.18，对齐原型 `.verify-fields` / `.result-preview`）：
 * 展示服务端的计划数量 / 实际流入 / 当前可执行上限，三列输入合格 / 返工 / 报废，
 * 结果预览只展示等式，未完成与上限由服务端重算。
 */
export function VerifyItemInputs({ item, input, note, errors = [], onChange, onNoteChange }: VerifyItemInputsProps) {
  const completed = completedQuantity(input);
  const blocker = verifyItemBlocker(item, input);
  const nodeText = NODE_LABELS[item.node as keyof typeof NODE_LABELS] ?? item.node;
  return (
    <Card size="small" title={`#${item.itemNo} ${item.productNo} ${item.productName} · ${nodeText}`}>
      <Space orientation="vertical" size={12} style={{ width: '100%' }}>
        <Space size={12} wrap>
          <Typography.Text>计划数量 {item.plannedQuantity}</Typography.Text>
          <Typography.Text>实际流入 {item.actualInflow}</Typography.Text>
          <Typography.Text strong>当前可执行上限 {item.executableQuantity}</Typography.Text>
          <Tag>{ITEM_STATUS_LABELS[item.status]}</Tag>
          {item.waitingUpstream && <Tag color="gold">等待上游</Tag>}
        </Space>

        <div className="verify-fields" style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)' }}>
          <div>
            <Typography.Text type="secondary">合格</Typography.Text>
            <InputNumber
              style={{ width: '100%', marginTop: 8 }}
              min={0}
              precision={0}
              aria-label="合格数量"
              value={input.qualifiedQuantity ?? undefined}
              onChange={value => onChange({ ...input, qualifiedQuantity: value ?? 0 })}
            />
          </div>
          <div>
            <Typography.Text type="secondary">返工</Typography.Text>
            <InputNumber
              style={{ width: '100%', marginTop: 8 }}
              min={0}
              precision={0}
              aria-label="返工数量"
              value={input.reworkQuantity ?? undefined}
              onChange={value => onChange({ ...input, reworkQuantity: value ?? 0 })}
            />
          </div>
          <div>
            <Typography.Text type="secondary">报废</Typography.Text>
            <InputNumber
              style={{ width: '100%', marginTop: 8 }}
              min={0}
              precision={0}
              aria-label="报废数量"
              value={input.scrapQuantity ?? undefined}
              onChange={value => onChange({ ...input, scrapQuantity: value ?? 0 })}
            />
          </div>
        </div>

        <Input
          style={{ width: 260 }}
          placeholder="备注"
          value={note}
          onChange={event => onNoteChange?.(event.target.value)}
        />

        <div className="result-preview" data-testid={`verify-preview-${item.id}`}>
          <div>
            <span>本次完成</span>
            <b>{completed}</b>
          </div>
          <div>
            <span>合格</span>
            <b>{input.qualifiedQuantity ?? 0}</b>
          </div>
          <div>
            <span>返工</span>
            <b>{input.reworkQuantity ?? 0}</b>
          </div>
          <div>
            <span>报废</span>
            <b>{input.scrapQuantity ?? 0}</b>
          </div>
        </div>

        <Tag color={completed > item.executableQuantity ? 'red' : 'blue'}>
          本次完成 {completed} = 合格 {input.qualifiedQuantity ?? 0} + 返工 {input.reworkQuantity ?? 0} + 报废{' '}
          {input.scrapQuantity ?? 0}
        </Tag>
        {blocker && <Alert type="error" showIcon title={blocker} />}
        {errors.length > 0 && (
          <Alert
            type="error"
            showIcon
            title="本明细字段错误"
            description={
              <ul style={{ margin: '4px 0 0', paddingLeft: 20 }}>
                {errors.map(entry => (
                  <li key={entry.field}>
                    <b>{entry.field}</b>：{entry.message}
                  </li>
                ))}
              </ul>
            }
          />
        )}
      </Space>
    </Card>
  );
}

/** 整批核验失败提示：明确「未产生任何事实」，不显示假成功。 */
export function VerifyBatchFailureAlert({ failure }: { failure: string | null }) {
  if (!failure) {
    return null;
  }
  return <Alert type="error" showIcon title="整批核验失败" description={failure} />;
}

export interface ProductionTaskVerifyPageProps {
  initialTask?: ProductionTaskView;
}

/**
 * 多明细批量核验页（阶段五 5.18）：一次点击提交整个任务的 `items[]`，不逐条发送写请求；
 * 前端只展示等式，上限与未完成由服务端在同一事务内重算；失败按明细定位并整批回滚。
 */
export function ProductionTaskVerifyPage({ initialTask }: ProductionTaskVerifyPageProps = {}) {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const { id } = useParams();
  const [task, setTask] = useState<ProductionTaskView | null>(initialTask ?? null);
  const [inputs, setInputs] = useState<Record<number, VerifyInput>>({});
  const [notes, setNotes] = useState<Record<number, string>>({});
  const [fieldErrors, setFieldErrors] = useState<ApiFieldError[]>([]);
  const [failure, setFailure] = useState<string | null>(null);
  const [result, setResult] = useState<ProductionVerificationView | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (initialTask) {
      return;
    }
    // 只读加载任务详情；核验写请求只在用户显式提交时发生一次。
    getProductionTask(id!)
      .then(setTask)
      .catch(error => message.error(describeApiError(error)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, initialTask]);

  const pendingItems = useMemo(
    () => (task?.items ?? []).filter(item => item.status === 'PENDING'),
    [task],
  );

  const locatedErrors = useMemo(() => mapFieldErrorsToItems(fieldErrors), [fieldErrors]);

  const blockers = pendingItems
    .map(item => verifyItemBlocker(item, inputs[item.id] ?? {}))
    .filter((value): value is string => value !== null);

  async function submit() {
    if (!task) {
      return;
    }
    setSubmitting(true);
    setFailure(null);
    setFieldErrors([]);
    try {
      const verification = await verifyProductionTask(
        task.id,
        verifySubmitItems(pendingItems.map(item => ({ item, input: inputs[item.id] ?? {}, note: notes[item.id] }))),
      );
      setResult(verification);
      message.success('核验已保存');
    } catch (error) {
      if (error instanceof YumiApiError) {
        setFieldErrors(error.fieldErrors);
      }
      setFailure(describeVerifyFailure(error));
      message.error(describeApiError(error));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="production-page">
      <Button type="link" className="back" onClick={() => navigate('/production')}>
        ← 返回工作台
      </Button>
      <div className="page-heading">
        <div>
          <Typography.Title level={4} style={{ margin: '0 0 6px' }}>
            批量核验
            <span className="heading-no">{task?.taskNo ?? ''}</span>
          </Typography.Title>
          <Space size={8} wrap>
            <Tag color="blue">一次提交整个任务</Tag>
            <Typography.Text type="secondary">失败整批回滚，不产生任何事实</Typography.Text>
          </Space>
        </div>
      </div>

      <div className="verify-body" style={{ maxWidth: 850 }}>
        {!task ? (
          <Alert type="info" showIcon title="加载中或任务不存在" />
        ) : (
          <Space orientation="vertical" size={12} style={{ width: '100%' }}>
            <VerifyBatchFailureAlert failure={failure} />

            {pendingItems.length === 0 ? (
              <Alert type="warning" showIcon title="没有可核验的待执行明细" />
            ) : (
              pendingItems.map(item => (
                <VerifyItemInputs
                  key={item.id}
                  item={item}
                  input={inputs[item.id] ?? {}}
                  note={notes[item.id]}
                  errors={locatedErrors.filter(entry => entry.itemIndex === pendingItems.indexOf(item))}
                  onChange={patch => setInputs(previous => ({ ...previous, [item.id]: patch }))}
                  onNoteChange={value => setNotes(previous => ({ ...previous, [item.id]: value }))}
                />
              ))
            )}

            <div className="verify-actions">
              <Button
                type="primary"
                loading={submitting}
                disabled={pendingItems.length === 0 || blockers.length > 0}
                onClick={() => void submit()}
              >
                提交核验（整个任务）
              </Button>
              <Button onClick={() => navigate(`/production/tasks/${task.id}`)}>返回只读详情</Button>
              <Typography.Text type="secondary" style={{ alignSelf: 'center' }}>
                一次点击只发送一个写请求；失败整批回滚。
              </Typography.Text>
            </div>

            {result && (
              <Card size="small" title="核验结果（服务端权威）">
                <Table
                  size="small"
                  rowKey="taskItemId"
                  pagination={false}
                  dataSource={result.items}
                  columns={[
                    { title: '明细', dataIndex: 'taskItemId', width: 80 },
                    { title: '计划', dataIndex: 'plannedQuantity', width: 70, align: 'right' as const },
                    { title: '本次完成', dataIndex: 'completedQuantity', width: 90, align: 'right' as const },
                    { title: '合格', dataIndex: 'qualifiedQuantity', width: 70, align: 'right' as const },
                    { title: '返工', dataIndex: 'reworkQuantity', width: 70, align: 'right' as const },
                    { title: '报废', dataIndex: 'scrapQuantity', width: 70, align: 'right' as const },
                    { title: '未完成', dataIndex: 'incompleteQuantity', width: 80, align: 'right' as const },
                  ]}
                />
              </Card>
            )}
          </Space>
        )}
      </div>
    </div>
  );
}
