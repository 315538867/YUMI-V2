import { apiFetch, type Money } from './client';

/* ---------------- 商品 ---------------- */

export type ProductStatus = 'ACTIVE' | 'DISABLED';

/**
 * 缝边剪袋变体预算（单件口径，公式 FP-PROD-20/21）：商品自身成本按不缝边剪袋口径保存，
 * 该变体单列展示「不缝边剪袋总成本 + 单件缝边人工成本（缝边标准分钟 × 时薪 ÷ 60）」，不写入商品快照。
 */
export interface SeamBudget {
  seamUnitCost: Money;
  seamFee: Money;
  totalCost: Money;
  referencePrice: Money;
}

export interface ProductSummary {
  id: number;
  productNo: string;
  name: string;
  status: ProductStatus;
  starLevelId: number;
  starName: string;
  salePrice: Money;
  totalCost: Money;
}

export interface ProductDetail extends ProductSummary {
  note?: string;
  imageFileId?: number;
  starLevelId: number;
  starName: string;
  starStdMinutes: number;
  weightG: number;
  lossRate: string;
  lossRatePercent: string;
  glueUnitPrice: Money;
  glueGrams: number;
  glueCost: Money;
  colorpasteUnitPrice: Money;
  colorpasteCost: Money;
  qty8h: number;
  qty6h: number;
  productLaborFee: Money;
  packagingTierId?: number;
  packagingTierName?: string;
  /** 包装档位标准分钟（整数） */
  packagingStdMinutes?: number;
  packagingCommission?: Money;
  packagingLaborFee: Money;
  /** 默认缝边剪袋类型（可空＝默认不缝边剪袋）与缝边价格，作为订单缝边定制的默认值 */
  seamTypeId?: number;
  seamTypeName?: string;
  /** 所选缝边种类的标准分钟快照（商品只提供默认值） */
  seamStdMinutes?: number;
  /** 单件缝边人工成本（服务端派生：标准分钟 × 全局时薪 ÷ 60） */
  seamUnitCost: Money;
  seamFee: Money;
  /** 缝边剪袋变体预算；未选默认缝边剪袋类型时为 null */
  seamBudget?: SeamBudget | null;
  boxLaborFee: Money;
  transportPackingFee: Money;
  dailySundriesFee: Money;
  rentUtilitiesFee: Money;
  moldAmortFee: Money;
  materialCost: Money;
  laborCost: Money;
  otherCost: Money;
  referencePrice: Money;
  estimatedProfit: Money;
  estimatedMarginRate: string;
  /** 生产产能：同一模具批次并行生产数量与每日批次数（正整数） */
  moldQuantity: number;
  dailyBatchLimit: number;
  /** 服务端派生：模具数量 × 每日批次数，不落库 */
  dailyMaxCapacity: number;
  version: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface ProductWriteRequest {
  name: string;
  note?: string;
  starLevelId: number;
  salePrice: Money;
  weightG: number;
  packagingTierId?: number;
  /** 商品自身包装提成；包装档位只提供标准分钟 */
  packagingCommission?: Money;
  /** 默认缝边剪袋类型（不传＝默认不缝边剪袋）；编辑时清空请改用 clearSeamType */
  seamTypeId?: number | null;
  /** 缝边价格（元/件），作为订单缝边定制的默认值 */
  seamFee?: Money;
  boxLaborFee?: Money;
  transportPackingFee?: Money;
  moldAmortFee?: Money;
  imageFileId?: number;
  /** 模具数量（正整数，必填） */
  moldQuantity: number;
  /** 每日批次数（正整数，必填） */
  dailyBatchLimit: number;
}

export function listProducts(params: { status?: string; name?: string } = {}): Promise<ProductSummary[]> {
  const query = new URLSearchParams();
  if (params.status) query.set('status', params.status);
  if (params.name) query.set('name', params.name);
  return apiFetch<ProductSummary[]>(`/api/products?${query.toString()}`);
}

export function getProduct(id: number | string): Promise<ProductDetail> {
  return apiFetch<ProductDetail>(`/api/products/${id}`);
}

export function createProduct(body: ProductWriteRequest): Promise<ProductDetail> {
  return apiFetch<ProductDetail>('/api/products', { method: 'POST', body: JSON.stringify(body) });
}

export function updateProduct(id: number | string, body: Partial<ProductWriteRequest> & { version: number; reason?: string; clearSeamType?: boolean; refreshMaterialPrices?: boolean; refreshGlobalReferences?: boolean }): Promise<ProductDetail> {
  return apiFetch<ProductDetail>(`/api/products/${id}`, { method: 'PATCH', body: JSON.stringify(body) });
}

export function toggleProduct(id: number | string, action: 'enable' | 'disable'): Promise<ProductDetail> {
  return apiFetch<ProductDetail>(`/api/products/${id}/${action}`, { method: 'POST', body: JSON.stringify({}) });
}

/* ---------------- 商品试算（只读，服务端唯一公式） ---------------- */

/** 试算输入：只含影响计算的字段，名称/图片等非计算资料不参与。 */
export interface ProductPreviewInput {
  starLevelId: number;
  salePrice: Money;
  weightG: number;
  packagingTierId?: number | null;
  packagingCommission?: Money;
  /** 默认缝边剪袋类型（null/未传＝默认不缝边剪袋）；只影响缝边剪袋变体预算，不进入商品自身成本 */
  seamTypeId?: number | null;
  /** 编辑试算时显式清空默认缝边剪袋类型；缺省合并下 null 与“未提供”无法区分 */
  clearSeamType?: boolean;
  seamFee?: Money;
  boxLaborFee?: Money;
  transportPackingFee?: Money;
  moldAmortFee?: Money;
  refreshMaterialPrices?: boolean;
  /** 管理员显式采纳最新全局设置：星级、档位与材料单价一并按当前全局重算（编辑试算与保存共用）。 */
  refreshGlobalReferences?: boolean;
}

/** 试算结果：金额与比例为服务端字符串，estimatedMarginRatePercent 可直接展示。 */
export interface ProductPreviewResult {
  glueGrams: number;
  glueCost: Money;
  colorpasteCost: Money;
  materialCost: Money;
  productLaborFee: Money;
  packagingLaborFee: Money;
  boxLaborFee: Money;
  laborCost: Money;
  otherCost: Money;
  totalCost: Money;
  referencePrice: Money;
  qty8h: number;
  qty6h: number;
  salePrice: Money;
  estimatedProfit: Money;
  estimatedMarginRate: string;
  estimatedMarginRatePercent: string;
  /** 缝边剪袋变体预算；未选默认缝边剪袋类型时为 null */
  seamBudget: SeamBudget | null;
}

/** 新建试算：POST /api/products/preview，只读、不要求幂等键、不产生业务写入。 */
export function previewNewProduct(
  body: ProductPreviewInput,
  signal?: AbortSignal,
): Promise<ProductPreviewResult> {
  return apiFetch<ProductPreviewResult>('/api/products/preview', {
    method: 'POST',
    body: JSON.stringify(body),
    signal,
  });
}

/** 编辑试算：POST /api/products/{id}/preview，沿用 PATCH 缺省合并语义，必须携带版本号。 */
export function previewProductUpdate(
  id: number | string,
  body: Partial<ProductPreviewInput> & { version: number },
  signal?: AbortSignal,
): Promise<ProductPreviewResult> {
  return apiFetch<ProductPreviewResult>(`/api/products/${id}/preview`, {
    method: 'POST',
    body: JSON.stringify(body),
    signal,
  });
}

/* ---------------- 客户 ---------------- */

export interface CustomerView {
  id?: number;
  customerNo: string;
  name: string;
  contact?: string;
  phone?: string;
  note?: string;
  defaultRecipient: string;
  defaultRecipientPhone: string;
  defaultRegion: string;
  defaultAddress: string;
  version: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface DuplicateCandidate {
  customerNo: string;
  name: string;
  phone?: string;
}

export interface CustomerCreateOutcome {
  created: boolean;
  duplicateCandidates?: DuplicateCandidate[];
}

export interface CustomerSummary {
  orderCount: string;
  totalOrdered: Money;
  totalReceived: Money;
  totalRefunded: Money;
}

export interface CustomerDetail extends CustomerView {
  summary: CustomerSummary;
}

export function listCustomers(params: { name?: string; phone?: string } = {}): Promise<CustomerView[]> {
  const query = new URLSearchParams();
  if (params.name) query.set('name', params.name);
  if (params.phone) query.set('phone', params.phone);
  return apiFetch<CustomerView[]>(`/api/customers?${query.toString()}`);
}

export function getCustomer(id: number | string): Promise<CustomerDetail> {
  return apiFetch<CustomerDetail>(`/api/customers/${id}`);
}

/** 创建客户：返回完整客户表示创建成功；返回 created=false 表示命中重复提示（未入库）。 */
export function createCustomer(
  body: Partial<CustomerView> & { duplicateConfirmed?: boolean },
): Promise<CustomerDetail | CustomerCreateOutcome> {
  return apiFetch<CustomerDetail | CustomerCreateOutcome>('/api/customers', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function updateCustomer(id: number | string, body: Partial<CustomerView> & { version: number; reason?: string }): Promise<CustomerDetail> {
  return apiFetch<CustomerDetail>(`/api/customers/${id}`, { method: 'PATCH', body: JSON.stringify(body) });
}

/* ---------------- 员工 ---------------- */

export interface WorkTypeView {
  /** 系统固定工序 code，接口出入参一律用 code */
  code: string;
  /** 当前名称，由工种目录提供、可改 */
  name: string;
}

/**
 * 系统内置四道工序：code 由系统固定、名称可由管理员在静态数据里修改。
 * 待 2.22 静态数据 API 落地后改为从 `GET /api/settings/static-data/WORK_TYPE` 读取。
 */
export const WORK_TYPE_OPTIONS: WorkTypeView[] = [
  { code: 'MAKING', name: '制作' },
  { code: 'PACKING_BAG', name: '捏毛装袋' },
  { code: 'SEAM_CUTTING', name: '缝边剪袋' },
  { code: 'OTHER', name: '其他' },
];
export type EmployeeStatus = 'ACTIVE' | 'LEFT';

export interface EmployeeView {
  id?: number;
  employeeNo: string;
  name: string;
  phone?: string;
  status: EmployeeStatus;
  firstHireDate: string;
  note?: string;
  workTypes: WorkTypeView[];
  version: number;
  createdAt?: string;
  updatedAt?: string;
}

export function listEmployees(params: { status?: string; name?: string } = {}): Promise<EmployeeView[]> {
  const query = new URLSearchParams();
  if (params.status) query.set('status', params.status);
  if (params.name) query.set('name', params.name);
  return apiFetch<EmployeeView[]>(`/api/employees?${query.toString()}`);
}

export function getEmployee(id: number | string): Promise<EmployeeView> {
  return apiFetch<EmployeeView>(`/api/employees/${id}`);
}

export function createEmployee(body: {
  name: string;
  phone?: string;
  firstHireDate: string;
  note?: string;
  /** 工种按系统固定 code 提交（响应返回 code 与名称） */
  workTypes: string[];
}): Promise<EmployeeView> {
  return apiFetch<EmployeeView>('/api/employees', { method: 'POST', body: JSON.stringify(body) });
}

export function updateEmployee(
  id: number | string,
  body: { version: number; name?: string; phone?: string; note?: string; workTypes?: string[]; reason?: string },
): Promise<EmployeeView> {
  return apiFetch<EmployeeView>(`/api/employees/${id}`, { method: 'PATCH', body: JSON.stringify(body) });
}

export function leaveEmployee(id: number | string, body: { reason: string; date: string }): Promise<EmployeeView> {
  return apiFetch<EmployeeView>(`/api/employees/${id}/leave`, { method: 'POST', body: JSON.stringify(body) });
}

export function rehireEmployee(id: number | string, body: { date: string }): Promise<EmployeeView> {
  return apiFetch<EmployeeView>(`/api/employees/${id}/rehire`, { method: 'POST', body: JSON.stringify(body) });
}
