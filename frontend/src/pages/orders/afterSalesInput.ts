import type { ShipmentView } from '../../api/shipments';

/**
 * 售后区域的可测纯逻辑（任务 8.10/8.12）：来源候选与售后生产计划入参。
 * 抽成纯函数以便用单元测试固定「只列已确认批次」「计划日期格式」「用途/工序透传」等口径。
 */

export interface AfterSalesSourceOption {
  value: number;
  label: string;
  quantity: number;
}

/** 售后来源候选：**只列已确认批次**的明细，并显示该明细已发数量（草稿批次不作为售后来源）。 */
export function confirmedSourceOptions(shipments: ShipmentView[]): AfterSalesSourceOption[] {
  return shipments
    .filter(shipment => shipment.status === 'CONFIRMED')
    // 售后补发批次是补发品，不是「原发货批次明细」，不能再次作为售后来源（服务端同样拒绝）
    .filter(shipment => !shipment.afterSalesReplacement)
    .flatMap(shipment =>
      shipment.items.map(item => ({
        value: item.id,
        label: `#${item.lineNo} ${item.productNo} ${item.productName} · ${shipment.shipmentNo} 已发 ${item.quantity}`,
        quantity: item.quantity,
      })),
    );
}

/** 售后生产计划入参：计划日期统一为 `YYYY-MM-DD`，用途/工序/员工/数量原样透传。 */
export function productionPlanPayload(values: {
  purpose: 'REWORK' | 'REPLACEMENT';
  node: string;
  planDate: { format: (pattern: string) => string };
  employeeId: number;
  quantity: number;
  note?: string;
}, afterSalesItemId: number) {
  return {
    afterSalesItemId,
    purpose: values.purpose,
    node: values.node,
    planDate: values.planDate.format('YYYY-MM-DD'),
    employeeId: values.employeeId,
    quantity: values.quantity,
    note: values.note,
  };
}
