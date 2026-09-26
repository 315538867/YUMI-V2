import { Badge, Button, Tag, Typography } from 'antd';
import {
  DERIVED_STATUS_COLORS,
  DERIVED_STATUS_LABELS,
  TASK_TYPE_LABELS,
  type IncompleteReminderView,
  type ProductionTaskView,
} from '../../api/production';
import { employeeBadgeColor, employeeColorClass, isTaskDone, taskHasIssue } from './productionLogic';

export interface ProductionTaskCardProps {
  task: ProductionTaskView;
  remindersByItemNode?: Map<string, IncompleteReminderView[]>;
  onOpen?: (task: ProductionTaskView) => void;
  onVerify?: (task: ProductionTaskView) => void;
}

/**
 * 周历任务卡（阶段五 5.17）：员工是卡片属性——固定色 Badge 与姓名同行，派生状态与提醒放第二行；
 * 明细（今日产品行）只展示数量，不重复员工色点。数量全部取服务端派生值。
 * 结构对齐原型 `.week-card`：员工色左边框、状态 Tag 行、任务号、订单/产品、计划/可执行。
 */
export function ProductionTaskCard({ task, remindersByItemNode, onOpen, onVerify }: ProductionTaskCardProps) {
  const color = employeeBadgeColor(task.employeeId);
  const colorClass = employeeColorClass(task.employeeId);
  const typeLabel = TASK_TYPE_LABELS[task.taskType] ?? task.taskType;
  const done = isTaskDone(task);
  const issue = taskHasIssue(task, remindersByItemNode);
  const hasPending = task.items.some(item => item.status === 'PENDING');
  const firstItem = task.items[0];
  const openReminders = task.items.flatMap(
    item => remindersByItemNode?.get(`${item.orderItemId}:${item.node}`) ?? [],
  ).filter(reminder => reminder.status === 'OPEN');
  const capacityNotice = task.items.find(item => item.capacityNotice)?.capacityNotice;
  const waitingUpstream = task.items.some(item => item.waitingUpstream);

  return (
    <div
      className={[
        'week-card',
        colorClass,
        done ? 'done' : '',
        issue ? 'issue' : '',
      ]
        .filter(Boolean)
        .join(' ')}
      data-testid={`week-card-${task.id}`}
      data-employee-color={color}
      role="button"
      tabIndex={0}
      onClick={() => onOpen?.(task)}
      onKeyDown={event => {
        if (event.key === 'Enter' || event.key === ' ') {
          onOpen?.(task);
        }
      }}
    >
      <div className="week-card-employee">
        <Badge color={color} text={task.employeeName} />
      </div>
      <div className="week-card-status">
        <Tag color={DERIVED_STATUS_COLORS[task.derivedStatus] ?? 'default'}>
          {DERIVED_STATUS_LABELS[task.derivedStatus] ?? task.derivedStatus}
        </Tag>
        <Tag color={task.taskType === 'REWORK' ? 'purple' : 'blue'}>{typeLabel}</Tag>
        {waitingUpstream ? <Tag color="gold">等待上游</Tag> : null}
        {openReminders.length > 0 ? <Tag color="orange">未完成 {openReminders.length}</Tag> : null}
        {hasPending && onVerify ? (
          <Button
            type="link"
            size="small"
            style={{ padding: 0, height: 'auto', fontSize: 11 }}
            onClick={event => {
              event.stopPropagation();
              onVerify(task);
            }}
          >
            核验
          </Button>
        ) : null}
      </div>
      <strong>{task.taskNo}</strong>
      <span>
        {firstItem
          ? `${firstItem.orderNo ?? `#${firstItem.orderId}`} · ${firstItem.productNo} ${firstItem.productName}`
          : '无明细'}
      </span>
      <small>
        计划 {firstItem?.plannedQuantity ?? '—'} · 实际流入 {firstItem?.actualInflow ?? '—'} · 可执行{' '}
        {firstItem?.executableQuantity ?? '—'}
        {task.items.length > 1 ? ` · 共 ${task.items.length} 条明细` : ''}
      </small>
      {capacityNotice ? <div className="warning">{capacityNotice}</div> : null}
      {task.note ? (
        <Typography.Text type="secondary" style={{ fontSize: 11, display: 'block', marginTop: 4 }}>
          {task.note}
        </Typography.Text>
      ) : null}
    </div>
  );
}
