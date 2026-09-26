import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { App } from 'antd';
import { ProductionFactTimeline } from '../../components/production/ProductionFactTimeline';
import type { ProductionFact } from '../../api/production';
import { sortFacts } from './productionLogic';

describe('事实追溯时间线（任务 5.18）', () => {
  it('sortsFactsByTimeTypeAndId', () => {
    const facts: ProductionFact[] = [
      { factType: 'CANCEL', factId: 9, factTime: '2026-09-23T10:00:00' },
      { factType: 'PLAN', factId: 3, factTime: '2026-09-23T08:00:00' },
      { factType: 'SCRAP', factId: 1, factTime: '2026-09-23T10:00:00' },
      { factType: 'REWORK_SOURCE', factId: 2, factTime: '2026-09-23T08:00:00' },
    ];
    const ordered = sortFacts(facts);
    // 服务端口径：factTime ASC → factType ASC → factId ASC
    expect(ordered.map(fact => `${fact.factTime}#${fact.factType}#${fact.factId}`)).toEqual([
      '2026-09-23T08:00:00#PLAN#3',
      '2026-09-23T08:00:00#REWORK_SOURCE#2',
      '2026-09-23T10:00:00#CANCEL#9',
      '2026-09-23T10:00:00#SCRAP#1',
    ]);
    // 不改动入参
    expect(facts[0].factType).toBe('CANCEL');
  });

  it('rendersFactsFromSingleEndpointIncludingParentAndVerificationBreakdown', () => {
    // 直接使用 `/api/production-tasks/{id}/facts` 的返回形状（含 note/referenceId/operator/reason）
    const facts: ProductionFact[] = [
      {
        factType: 'VERIFICATION',
        factId: 7,
        factTime: '2026-09-23T09:00:00',
        node: 'MAKING',
        quantity: 10,
        orderItemId: 11,
        referenceId: 7,
        operator: 'admin',
        reason: null,
        note: '合格 6 / 返工 2 / 报废 1 / 未完成 1',
      },
      {
        factType: 'REWORK_FACT',
        factId: 8,
        factTime: '2026-09-23T09:00:01',
        node: 'MAKING',
        quantity: 2,
        orderItemId: 11,
        referenceId: 7,
        operator: 'admin',
        reason: null,
        note: '核验产生返工事实',
      },
      {
        factType: 'REWORK_SOURCE',
        factId: 12,
        factTime: '2026-09-23T09:00:02',
        node: 'MAKING',
        quantity: 5,
        orderItemId: 11,
        referenceId: 4,
        operator: 'admin',
        reason: '来料不良',
        note: '第 1 轮，已安排 0 / 5',
      },
      {
        factType: 'SCRAP',
        factId: 3,
        factTime: '2026-09-23T10:00:00',
        node: 'MAKING',
        quantity: 1,
        orderItemId: 11,
        referenceId: 7,
        operator: 'admin',
        reason: '破损',
        note: '报废事实',
      },
      {
        factType: 'CANCEL',
        factId: 5,
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
    const html = renderToStaticMarkup(
      <App>
        <ProductionFactTimeline facts={facts} />
      </App>,
    );
    // 核验事件的合格/返工/报废/未完成分解来自 note
    expect(html).toContain('核验');
    expect(html).toContain('合格 6 / 返工 2 / 报废 1 / 未完成 1');
    // 返工事实指向父核验
    expect(html).toContain('返工事实');
    expect(html).toContain('父核验 #7');
    // 返工来源保留自身来源 id 与父来源
    expect(html).toContain('返工来源');
    expect(html).toContain('来源 #12');
    expect(html).toContain('父来源 #4');
    expect(html).toContain('原因 来料不良');
    // 报废 / 取消原因与操作人
    expect(html).toContain('报废');
    expect(html).toContain('原因 破损');
    expect(html).toContain('取消');
    expect(html).toContain('原因 订单取消');
    expect(html).toContain('admin');
    expect(html).toContain('数量 5');
  });

  it('localizesSourceAndStatusTokensInFactNotes', () => {
    // 服务端 note 里带机器词：PLAN 带来源类型、提醒/预占带状态，界面必须显示中文
    const facts: ProductionFact[] = [
      {
        factType: 'PLAN',
        factId: 11,
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
        factType: 'INCOMPLETE',
        factId: 21,
        factTime: '2026-09-23T12:00:00',
        node: 'MAKING',
        quantity: 4,
        orderItemId: 11,
        referenceId: 7,
        operator: 'admin',
        reason: null,
        note: '未完成提醒 OPEN',
      },
      {
        factType: 'OVERTIME_PREEMPTION',
        factId: 31,
        factTime: '2026-09-23T13:00:00',
        node: 'MAKING',
        quantity: 6,
        orderItemId: 11,
        referenceId: 2,
        operator: 'admin',
        reason: null,
        note: '预占 ACTIVE',
      },
    ];
    const html = renderToStaticMarkup(
      <App>
        <ProductionFactTimeline facts={facts} />
      </App>,
    );
    expect(html).toContain('计划（制作） · 订单需求');
    expect(html).toContain('未完成提醒 待处理');
    expect(html).toContain('预占 有效');
    for (const token of ['ORDER', 'OPEN', 'ACTIVE']) {
      expect(new RegExp(`\\b${token}\\b`).test(html)).toBe(false);
    }
  });
});
