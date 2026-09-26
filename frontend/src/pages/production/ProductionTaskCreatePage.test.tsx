import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { App } from 'antd';
import { ProductionItemContextPanel, ProductionTaskCreatePage } from './ProductionTaskCreatePage';
import {
  CREATE_TYPE_OPTIONS,
  abandonCreateForm,
  buildCreatePageReads,
  buildWorkbenchReads,
  isWriteRequest,
  shiftWeek,
  weekRange,
} from './productionLogic';

function renderCreatePage(): string {
  return renderToStaticMarkup(
    <App>
      <MemoryRouter initialEntries={['/production/tasks/new']}>
        <Routes>
          <Route path="/production/tasks/new" element={<ProductionTaskCreatePage />} />
        </Routes>
      </MemoryRouter>
    </App>,
  );
}

describe('统一新建排班（任务 5.17）', () => {
  it('selectsTypeInsideUnifiedForm', () => {
    const html = renderCreatePage();
    // 类型选择在同一个表单内，不是四个分散的主创建按钮
    expect(html).toContain('排班类型');
    expect(html).toContain('类型');
    expect(html).toContain('新建排班');
    expect(html).not.toContain('新建计划');
    expect(html).not.toContain('新建超额任务');
    expect(html).not.toContain('新建其他排班');
    // 四种类型都在表单内可选
    expect(CREATE_TYPE_OPTIONS.map(option => option.value)).toEqual(['NORMAL', 'REWORK', 'OVERTIME', 'OTHER']);
    expect(CREATE_TYPE_OPTIONS.map(option => option.label).join('|')).toContain('超额预占');
    expect(CREATE_TYPE_OPTIONS.map(option => option.label).join('|')).toContain('其他排班');
  });

  it('doesNotCalculateAuthoritativeQuantity', () => {
    // 服务端给出的估算分钟 777 与 计划13 × 标准11 = 143 故意不一致：
    // 页面必须原样展示服务端值，绝不出现前端乘积。
    const html = renderToStaticMarkup(
      <ProductionItemContextPanel
        context={{
          orderNo: 'YM00001',
          lineNo: 2,
          productNo: 'P0009',
          productName: '小熊',
          node: 'MAKING',
          plannedQuantity: 13,
          standardMinutes: 11,
          estimatedMinutes: 777,
          actualInflow: 5,
          executableQuantity: 4,
          returnBalance: 3,
          sourceLabel: '订单需求',
          sourceBalance: 8,
        }}
      />,
    );
    expect(html).toContain('777');
    expect(html).not.toContain('143');
    expect(html).toContain('计划数量');
    expect(html).toContain('实际流入');
    expect(html).toContain('当前可执行');
    expect(html).toContain('回转余额');
    expect(html).toContain('标准分钟（服务端冻结）11');
  });

  it('cancelAndRefreshDoNotWrite', () => {
    // 取消/放弃表单不发起任何请求
    expect(abandonCreateForm()).toEqual([]);
    // 刷新与切换周历只发 GET
    const thisWeek = buildWorkbenchReads({ dateFrom: weekRange('2026-09-23')[0], dateTo: weekRange('2026-09-23')[1] });
    const nextWeek = buildWorkbenchReads({
      dateFrom: weekRange(shiftWeek('2026-09-23', 1))[0],
      dateTo: weekRange(shiftWeek('2026-09-23', 1))[1],
    });
    for (const request of [...thisWeek, ...nextWeek, ...buildCreatePageReads(11)]) {
      expect(isWriteRequest(request)).toBe(false);
      expect(request.method).toBe('GET');
    }
    // 分类器本身能识别写方法，证明断言有效
    expect(isWriteRequest({ method: 'POST' })).toBe(true);
    expect(isWriteRequest({ method: 'PATCH' })).toBe(true);
  });

  it('rendersPrototypeCreateGridWithRightRail', () => {
    const html = renderCreatePage();
    // 类型行 + 三列上下文字段 + 左侧表单 / 310px 右栏
    expect(html).toContain('class="type-row"');
    expect(html).toContain('class="context-fields"');
    expect(html).toContain('class="create-grid"');
    expect(html).toContain('create-employee');
    expect(html).toContain('create-worktype');
    expect(html).toContain('create-date');
    // 右栏摘要 + 纵向动作列
    expect(html).toContain('class="context-name"');
    expect(html).toContain('class="allocation-actions"');
    expect(html).toContain('本次排班');
    // 310px 右栏 + 三列上下文字段（原型 .create-grid / .context-fields）
    expect(html).toContain('grid-template-columns:minmax(0, 1fr) 310px');
    expect(html).toContain('grid-template-columns:1fr 1.35fr 0.8fr');
    // NORMAL 的明细区块（.allocation）在左栏，添加明细在右栏动作列
    expect(html).toMatch(/class="[^"]*\ballocation\b/);
    expect(html).toContain('任务明细（可多订单多产品）');
  });

  it('rendersOtherScheduleTimeFieldsInLeftColumn', () => {
    const html = renderToStaticMarkup(
      <App>
        <MemoryRouter initialEntries={['/production/tasks/new']}>
          <Routes>
            <Route
              path="/production/tasks/new"
              element={<ProductionTaskCreatePage initialScheduleType="OTHER" />}
            />
          </Routes>
        </MemoryRouter>
      </App>,
    );
    // OTHER 用 .time-fields 并排小时/分钟，右栏动作列仍保留
    expect(html).toContain('class="time-fields"');
    expect(html).toContain('分钟（0–59）');
    expect(html).toContain('class="allocation-actions"');
    expect(html).toContain('本次排班');
  });
});
