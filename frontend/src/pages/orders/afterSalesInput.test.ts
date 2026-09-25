import { describe, expect, it } from 'vitest';

import { confirmedSourceOptions, productionPlanPayload } from './afterSalesInput';
import type { ShipmentView } from '../../api/shipments';

function shipment(overrides: Partial<ShipmentView>): ShipmentView {
  return {
    id: 1,
    shipmentNo: 'SH000001',
    status: 'DRAFT',
    shipmentDate: '2026-09-25',
    items: [],
    ...overrides,
  } as ShipmentView;
}

describe('售后来源候选（任务 8.10）', () => {
  it('只列已确认批次，草稿与作废批次不作为售后来源', () => {
    const options = confirmedSourceOptions([
      shipment({
        id: 1,
        shipmentNo: 'SH000001',
        status: 'CONFIRMED',
        items: [
          { id: 11, lineNo: 1, productNo: 'P1', productName: '商品一', quantity: 4 },
          { id: 12, lineNo: 2, productNo: 'P2', productName: '商品二', quantity: 6 },
        ],
      } as Partial<ShipmentView>),
      shipment({
        id: 2,
        shipmentNo: 'SH000002',
        status: 'DRAFT',
        items: [{ id: 21, lineNo: 1, productNo: 'P1', productName: '商品一', quantity: 3 }],
      } as Partial<ShipmentView>),
      shipment({
        id: 3,
        shipmentNo: 'SH000003',
        status: 'VOIDED',
        items: [{ id: 31, lineNo: 1, productNo: 'P1', productName: '商品一', quantity: 2 }],
      } as Partial<ShipmentView>),
    ]);

    expect(options.map(option => option.value)).toEqual([11, 12]);
    expect(options[0].label).toBe('#1 P1 商品一 · SH000001 已发 4');
    expect(options[0].quantity).toBe(4);
  });

  it('售后补发批次不作为售后来源候选（补发品不是原发货批次明细）', () => {
    const options = confirmedSourceOptions([
      shipment({
        id: 1,
        shipmentNo: 'SH000016',
        status: 'CONFIRMED',
        items: [{ id: 11, lineNo: 1, productNo: 'P1', productName: '商品一', quantity: 4 }],
      } as Partial<ShipmentView>),
      shipment({
        id: 2,
        shipmentNo: 'SH000017',
        status: 'CONFIRMED',
        afterSalesReplacement: true,
        items: [{ id: 21, lineNo: 1, productNo: 'P1', productName: '商品一', quantity: 2 }],
      } as Partial<ShipmentView>),
    ]);

    expect(options.map(option => option.value)).toEqual([11]);
  });

  it('没有任何已确认批次时返回空候选（不给出草稿来源）', () => {
    expect(
      confirmedSourceOptions([
        shipment({ status: 'DRAFT', items: [{ id: 21, lineNo: 1, productNo: 'P1', productName: '商品一', quantity: 3 }] } as Partial<ShipmentView>),
      ]),
    ).toEqual([]);
  });
});

describe('售后生产计划入参（任务 8.12）', () => {
  it('计划日期格式化为 YYYY-MM-DD，其余字段原样透传', () => {
    const payload = productionPlanPayload(
      {
        purpose: 'REWORK',
        node: 'MAKING',
        planDate: { format: (pattern: string) => (pattern === 'YYYY-MM-DD' ? '2026-09-26' : 'WRONG') },
        employeeId: 7,
        quantity: 2,
        note: '验收-售后返工',
      },
      17,
    );

    expect(payload).toEqual({
      afterSalesItemId: 17,
      purpose: 'REWORK',
      node: 'MAKING',
      planDate: '2026-09-26',
      employeeId: 7,
      quantity: 2,
      note: '验收-售后返工',
    });
  });

  it('补发生产用途同样透传，备注可空', () => {
    const payload = productionPlanPayload(
      {
        purpose: 'REPLACEMENT',
        node: 'SEAM_CUTTING',
        planDate: { format: () => '2026-09-26' },
        employeeId: 9,
        quantity: 4,
      },
      18,
    );

    expect(payload.purpose).toBe('REPLACEMENT');
    expect(payload.node).toBe('SEAM_CUTTING');
    expect(payload.quantity).toBe(4);
    expect(payload.note).toBeUndefined();
  });
});
