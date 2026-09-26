import { Select, Space } from 'antd';
import { DERIVED_STATUS_LABELS, TASK_TYPE_LABELS } from '../../api/production';

export interface FilterOption {
  value: number;
  label: string;
}

export interface ProductionFilterValue {
  employeeId?: number;
  workTypeId?: number;
  taskType?: string;
  status?: string;
  orderId?: number;
  productId?: number;
}

export interface ProductionFiltersProps {
  value: ProductionFilterValue;
  employees: FilterOption[];
  workTypes: FilterOption[];
  orders: FilterOption[];
  products: FilterOption[];
  onChange: (patch: Partial<ProductionFilterValue>) => void;
}

const TASK_TYPE_OPTIONS = Object.entries(TASK_TYPE_LABELS).map(([value, label]) => ({ value, label }));
const STATUS_OPTIONS = Object.entries(DERIVED_STATUS_LABELS).map(([value, label]) => ({ value, label }));

/**
 * 工作台筛选（阶段五 5.17）：员工只是属性筛选（下拉），不是导航分组；
 * 切换员工只改查询条件，不改变日期横向周历的列结构。
 */
export function ProductionFilters({
  value,
  employees,
  workTypes,
  orders,
  products,
  onChange,
}: ProductionFiltersProps) {
  return (
    <Space size={8} wrap data-testid="production-filters">
      <Select
        style={{ width: 180 }}
        allowClear
        showSearch
        optionFilterProp="label"
        placeholder="员工（属性筛选）"
        aria-label="员工筛选"
        value={value.employeeId}
        options={employees}
        onChange={next => onChange({ employeeId: next })}
      />
      <Select
        style={{ width: 150 }}
        allowClear
        placeholder="工序"
        aria-label="工序筛选"
        value={value.workTypeId}
        options={workTypes}
        onChange={next => onChange({ workTypeId: next })}
      />
      <Select
        style={{ width: 140 }}
        allowClear
        placeholder="任务类型"
        aria-label="任务类型筛选"
        value={value.taskType}
        options={TASK_TYPE_OPTIONS}
        onChange={next => onChange({ taskType: next })}
      />
      <Select
        style={{ width: 140 }}
        allowClear
        placeholder="派生状态"
        aria-label="状态筛选"
        value={value.status}
        options={STATUS_OPTIONS}
        onChange={next => onChange({ status: next })}
      />
      <Select
        style={{ width: 200 }}
        allowClear
        showSearch
        optionFilterProp="label"
        placeholder="订单"
        aria-label="订单筛选"
        value={value.orderId}
        options={orders}
        onChange={next => onChange({ orderId: next })}
      />
      <Select
        style={{ width: 200 }}
        allowClear
        showSearch
        optionFilterProp="label"
        placeholder="产品"
        aria-label="产品筛选"
        value={value.productId}
        options={products}
        onChange={next => onChange({ productId: next })}
      />
    </Space>
  );
}
