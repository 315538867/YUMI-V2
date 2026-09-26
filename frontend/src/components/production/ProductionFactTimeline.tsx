import { Space, Table, Tag, Typography } from 'antd';
import { FACT_TYPE_LABELS, type ProductionFact } from '../../api/production';
import { NODE_LABELS } from '../../api/inventory';
import { factReferenceLabel, factSummary, sortFacts, formatFactTime } from '../../pages/production/productionLogic';

export interface ProductionFactTimelineProps {
  facts: ProductionFact[];
}

function nodeLabel(node?: string | null): string {
  if (!node) {
    return '—';
  }
  return NODE_LABELS[node as keyof typeof NODE_LABELS] ?? node;
}

/**
 * 事实追溯时间线（阶段五 5.18）：数据来自单一 `GET /api/production-tasks/{id}/facts`，
 * 服务端已按 `factTime ASC, factType ASC, factId ASC` 排序，这里保留 `sortFacts` 作为防御性二次排序。
 * 展示来源 id、父核验/父来源、工序、操作人、原因与数量；覆盖计划到工时更正的 13 类事件。
 */
export function ProductionFactTimeline({ facts }: ProductionFactTimelineProps) {
  const ordered = sortFacts(facts);
  return (
    <Table<ProductionFact>
      size="small"
      rowKey={fact => `${fact.factType}-${fact.factId}`}
      pagination={false}
      scroll={{ x: 1280 }}
      dataSource={ordered}
      locale={{ emptyText: '暂无可追溯事实' }}
      columns={[
        {
          title: '时间',
          dataIndex: 'factTime',
          width: 170,
          render: (value: string | null) => (
            <Typography.Text type="secondary">{formatFactTime(value)}</Typography.Text>
          ),
        },
        {
          title: '事件',
          dataIndex: 'factType',
          width: 110,
          render: (value: ProductionFact['factType']) => <Tag>{FACT_TYPE_LABELS[value] ?? value}</Tag>,
        },
        { title: '摘要', key: 'summary', render: (_: unknown, row) => factSummary(row) },
        {
          title: '数量',
          dataIndex: 'quantity',
          width: 80,
          align: 'right' as const,
          render: (value?: number | null) => (value == null ? '—' : `数量 ${value}`),
        },
        {
          title: '事实 #',
          dataIndex: 'factId',
          width: 90,
          render: (value: number, row) =>
            row.factType === 'REWORK_SOURCE' ? `来源 #${value}` : `事实 #${value}`,
        },
        {
          title: '来源 / 父级',
          key: 'reference',
          width: 120,
          render: (_: unknown, row) => factReferenceLabel(row) ?? '—',
        },
        {
          title: '工序',
          dataIndex: 'node',
          width: 100,
          render: (value?: string | null) => nodeLabel(value),
        },
        {
          title: '操作人',
          dataIndex: 'operator',
          width: 110,
          render: (value?: string | null) => value ?? '—',
        },
        {
          title: '原因',
          dataIndex: 'reason',
          width: 160,
          render: (value?: string | null) => (value ? `原因 ${value}` : '—'),
        },
      ]}
    />
  );
}

/** 供页面在时间线旁展示事件计数的小组件。 */
export function ProductionFactSummary({ facts }: ProductionFactTimelineProps) {
  const ordered = sortFacts(facts);
  return (
    <Space size={8} wrap>
      {ordered.map(fact => (
        <Tag key={`${fact.factType}-${fact.factId}`} color="blue">
          {FACT_TYPE_LABELS[fact.factType] ?? fact.factType}
        </Tag>
      ))}
    </Space>
  );
}
