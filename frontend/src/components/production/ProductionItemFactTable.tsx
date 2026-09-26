import { Space, Table, Tag, Typography } from 'antd';
import {
  ITEM_STATUS_LABELS,
  SOURCE_TYPE_LABELS,
  type ProductionTaskItemView,
} from '../../api/production';
import { NODE_LABELS } from '../../api/inventory';

export interface ProductionItemFactTableProps {
  items: ProductionTaskItemView[];
}

function nodeLabel(node: ProductionTaskItemView['node']): string {
  return NODE_LABELS[node as keyof typeof NODE_LABELS] ?? node;
}

/**
 * 任务明细事实表（阶段五 5.18）：把「计划数量 / 实际流入 / 当前可执行 / 已核验处理」分列展示，
 * 明细状态与来源、取消历史一并可见；三道工序数量不相加。
 */
import { formatFactTime } from '../../pages/production/productionLogic';

export function ProductionItemFactTable({ items }: ProductionItemFactTableProps) {
  return (
    <Table<ProductionTaskItemView>
      size="small"
      rowKey="id"
      pagination={false}
      dataSource={items}
      scroll={{ x: 1400 }}
      locale={{ emptyText: '暂无任务明细' }}
      columns={[
        { title: '明细', dataIndex: 'itemNo', width: 60, render: (value: number) => `#${value}` },
        {
          title: '订单 / 产品',
          key: 'orderProduct',
          render: (_: unknown, row) =>
            `${row.orderNo ?? `#${row.orderId}`} · #${row.lineNo} · ${row.productNo} ${row.productName}`,
        },
        { title: '工序', dataIndex: 'node', width: 100, render: (value: ProductionTaskItemView['node']) => nodeLabel(value) },
        {
          title: '来源',
          key: 'source',
          width: 140,
          render: (_: unknown, row) => (
            <Space size={4}>
              <Tag>{SOURCE_TYPE_LABELS[row.sourceType] ?? row.sourceType}</Tag>
              <span>来源 #{row.sourceId}</span>
            </Space>
          ),
        },
        { title: '计划数量', dataIndex: 'plannedQuantity', width: 90, align: 'right' as const },
        { title: '实际流入', dataIndex: 'actualInflow', width: 90, align: 'right' as const },
        {
          title: '当前可执行',
          dataIndex: 'executableQuantity',
          width: 100,
          align: 'right' as const,
          render: (value: number) => <b>{value}</b>,
        },
        { title: '已核验处理', dataIndex: 'verifiedProcessedQuantity', width: 100, align: 'right' as const },
        {
          title: '核验分解（合格 / 返工 / 报废 / 未完成）',
          key: 'verification',
          width: 260,
          render: (_: unknown, row) => {
            const verification = row.verification;
            if (!verification) {
              return '未核验';
            }
            return (
              <Space orientation="vertical" size={2}>
                <span data-testid={`item-verification-${row.id}`}>
                  {`合格 ${verification.qualifiedQuantity} / 返工 ${verification.reworkQuantity} / 报废 ${verification.scrapQuantity} / 未完成 ${verification.incompleteQuantity}`}
                </span>
                <Typography.Text type="secondary">
                  {`核验人 ${verification.verifiedBy ?? '—'} · ${formatFactTime(verification.verifiedAt)}`}
                </Typography.Text>
                {verification.note ? (
                  <Typography.Text type="secondary">{`备注 ${verification.note}`}</Typography.Text>
                ) : null}
              </Space>
            );
          },
        },
        {
          title: '标准 / 估算分钟',
          key: 'minutes',
          width: 140,
          render: (_: unknown, row) => `${row.standardMinutes ?? '—'} / ${row.estimatedMinutes ?? '—'}`,
        },
        {
          title: '状态',
          key: 'status',
          width: 110,
          render: (_: unknown, row) => (
            <Space size={4}>
              <Tag>{ITEM_STATUS_LABELS[row.status]}</Tag>
              {row.waitingUpstream && <Tag color="gold">等待上游</Tag>}
            </Space>
          ),
        },
        {
          title: '取消历史',
          key: 'cancel',
          width: 200,
          render: (_: unknown, row) =>
            row.status === 'CANCELLED'
              ? `原因 ${row.cancelReason ?? '—'}（操作人 ${row.cancelledBy ?? '—'}）`
              : '—',
        },
      ]}
    />
  );
}
