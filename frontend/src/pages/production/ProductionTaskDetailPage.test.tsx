import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { App } from 'antd';
import { ProductionTaskDetailPage } from './ProductionTaskDetailPage';
import type { ProductionFact, ProductionTaskItemView, ProductionTaskView } from '../../api/production';

function item(overrides: Partial<ProductionTaskItemView> = {}): ProductionTaskItemView {
  return {
    id: 101,
    itemNo: 1,
    orderId: 1,
    orderNo: 'YM00001',
    orderItemId: 11,
    lineNo: 2,
    productId: 21,
    productNo: 'P0001',
    productName: '草莓熊',
    node: 'MAKING',
    plannedQuantity: 10,
    sourceType: 'ORDER',
    sourceId: 11,
    standardMinutes: 6,
    estimatedMinutes: 60,
    actualInflow: 8,
    verifiedProcessedQuantity: 0,
    executableQuantity: 8,
    waitingUpstream: false,
    status: 'PENDING',
    cancelledBy: null,
    cancelReason: null,
    verification: null,
    version: 0,
    ...overrides,
  };
}

const task: ProductionTaskView = {
  id: 1,
  taskNo: 'PT000001',
  taskDate: '2026-09-23',
  employeeId: 7,
  employeeName: '张三',
  workTypeId: 2,
  workTypeName: '制作',
  taskType: 'NORMAL',
  note: '加急',
  derivedStatus: 'PARTIALLY_VERIFIED',
  items: [
    item({
      id: 101,
      itemNo: 1,
      status: 'VERIFIED',
      verifiedProcessedQuantity: 6,
      executableQuantity: 0,
      verification: {
        plannedQuantity: 10,
        completedQuantity: 6,
        qualifiedQuantity: 4,
        reworkQuantity: 1,
        scrapQuantity: 1,
        incompleteQuantity: 4,
        note: '首检',
        verifiedBy: 'admin',
        verifiedAt: '2026-09-23 18:00:00',
      },
    }),
    item({ id: 102, itemNo: 2, status: 'CANCELLED', cancelReason: '订单取消', cancelledBy: 'admin' }),
  ],
  version: 3,
};

// 与 `GET /api/production-tasks/{id}/facts` 返回形状一致
const facts: ProductionFact[] = [
  {
    factType: 'PLAN',
    factId: 101,
    factTime: '2026-09-23T08:00:00',
    node: 'MAKING',
    quantity: 10,
    orderItemId: 11,
    referenceId: null,
    operator: 'admin',
    reason: null,
    note: 'ORDER',
  },
  {
    factType: 'VERIFICATION',
    factId: 7,
    factTime: '2026-09-23T09:00:00',
    node: 'MAKING',
    quantity: 6,
    orderItemId: 11,
    referenceId: 7,
    operator: 'admin',
    reason: null,
    note: '合格 4 / 返工 1 / 报废 1 / 未完成 4',
  },
  {
    factType: 'CANCEL',
    factId: 102,
    factTime: '2026-09-23T11:00:00',
    node: 'MAKING',
    quantity: 10,
    orderItemId: 11,
    referenceId: null,
    operator: 'admin',
    reason: '订单取消',
    note: '明细取消',
  },
];

function renderDetail(): string {
  return renderToStaticMarkup(
    <App>
      <MemoryRouter initialEntries={['/production/tasks/1']}>
        <Routes>
          <Route
            path="/production/tasks/:id"
            element={<ProductionTaskDetailPage initialTask={task} initialFacts={facts} />}
          />
        </Routes>
      </MemoryRouter>
    </App>,
  );
}

describe('任务详情只读页（任务 5.18）', () => {
  it('rendersReadOnlySnapshotsAndDerivedQuantities', () => {
    const html = renderDetail();
    expect(html).toContain('只读');
    expect(html).toContain('PT000001');
    expect(html).toContain('YM00001');
    expect(html).toContain('草莓熊');
    expect(html).toContain('计划数量');
    expect(html).toContain('实际流入');
    expect(html).toContain('当前可执行');
    // 服务端快照与派生量原样展示
    expect(html).toContain('8');
    expect(html).toContain('6 / 60');
    // 已核验处理仍在
    expect(html).toContain('已核验处理');
    // 取消历史可见
    expect(html).toContain('订单取消');
    expect(html).toContain('admin');
    // 事实时间线来自 /facts 单一端点
    expect(html).toContain('事实追溯时间线');
    expect(html).toContain('明细取消');
    expect(html).toContain('父核验 #7');
  });

  it('rendersPerItemVerificationBreakdown', () => {
    const html = renderDetail();
    // 逐明细核验分解：合格/返工/报废/未完成 + 核验人/时间
    expect(html).toContain('合格 4 / 返工 1 / 报废 1 / 未完成 4');
    expect(html).toContain('核验人 admin');
    expect(html).toContain('2026-09-23 18:00:00');
    // 未核验明细显示「未核验」
    expect(html).toContain('未核验');
  });

  it('doesNotRenderEditFormOnViewPage', () => {
    const html = renderDetail();
    expect(html).not.toContain('<form');
    expect(html).not.toContain('保存');
    expect(html).not.toContain('编辑');
    expect(html).not.toContain('ant-input-number');
  });

  it('rendersBackMetricsStripSectionCardsAndFollow', () => {
    const html = renderDetail();
    // 返回链接（26px，标题之上）
    expect(html).toContain(' back"');
    expect(html).toContain('← 返回工作台');
    // 任务编号作为 heading-no（14px 灰）
    expect(html).toContain('class="heading-no"');
    expect(html).toContain('PT000001');
    // 任务类型跟在标题名字后面，不再占任务头的独立字段行
    expect(html).toContain('heading-type');
    expect(html).toContain('正常生产');
    // 操作按钮统一在底部 .follow，标题区不再有动作按钮
    expect(html).not.toContain('去核验');
    // 指标条：4 格，只统计记录条数，不做商品数量算术
    expect(html).toContain('data-testid="task-metrics"');
    expect(html).toContain('class="metrics"');
    for (const label of ['明细数', '待执行明细', '已核验明细', '事实条数']) {
      expect(html).toContain(label);
    }
    // section-card 分块（任务头 / 明细快照 / 事实时间线）
    expect(html.match(/section-card/g)?.length ?? 0).toBeGreaterThanOrEqual(3);
    // 后续操作块（.follow + .follow-row）
    expect(html).toContain('data-testid="task-follow"');
    expect(html).toContain('后续操作');
    expect(html.match(/follow-row/g)).toHaveLength(2);
    // 指标条 4 列（原型 .metrics）
    expect(html).toContain('grid-template-columns:repeat(4, 1fr)');
  });
});
