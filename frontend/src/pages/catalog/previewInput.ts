import type { ProductDetail, ProductPreviewInput, ProductPreviewResult } from '../../api/catalog';

/** 表单中参与计算的字段（对应 ProductPreviewInput）。 */
export interface PreviewFormValues {
  starLevelId?: number | null;
  salePrice?: string | number | null;
  weightG?: number | null;
  packagingTierId?: number | null;
  packagingCommission?: string | number | null;
  seamTypeId?: number | null;
  seamFee?: string | number | null;
  boxLaborFee?: string | number | null;
  transportPackingFee?: string | number | null;
  moldAmortFee?: string | number | null;
  refreshMaterialPrices?: boolean;
  refreshGlobalReferences?: boolean;
}

const DECIMAL_TEXT = /^\d+(\.\d+)?$/;

function decimalText(value: string | number | null | undefined): string | null {
  if (value === null || value === undefined) {
    return null;
  }
  const text = String(value).trim();
  return DECIMAL_TEXT.test(text) ? text : null;
}

/** 未填 → undefined；非法文本 → null；合法 → 原文（保持字符串精度）。 */
function optionalDecimal(value: string | number | null | undefined): string | null | undefined {
  if (value === null || value === undefined || String(value).trim() === '') {
    return undefined;
  }
  const text = String(value).trim();
  return DECIMAL_TEXT.test(text) ? text : null;
}

/**
 * 编辑时清空默认缝边剪袋类型必须显式声明：试算与保存共用 PATCH 缺省合并语义，
 * 入参中 `null` 与“未提供”无法区分，只传 `null` 会被服务端理解为保持原值。
 */
export function isSeamCleared(values: { seamTypeId?: number | null }): boolean {
  return values.seamTypeId === null || values.seamTypeId === undefined;
}

/**
 * 从表单值收集试算输入：任一计算字段缺失或为非法文本时返回 null，
 * 页面显示“待填写”并由表单字段规则提示非法文本，不发起请求。
 */
export function collectPreviewInput(values: PreviewFormValues): ProductPreviewInput | null {
  if (typeof values.starLevelId !== 'number') {
    return null;
  }
  const weightG = values.weightG;
  if (typeof weightG !== 'number' || !Number.isInteger(weightG) || weightG < 0) {
    return null;
  }
  const salePrice = decimalText(values.salePrice);
  if (salePrice === null) {
    return null;
  }
  const optional = [
    values.packagingCommission,
    values.boxLaborFee,
    values.transportPackingFee,
    values.moldAmortFee,
    values.seamFee,
  ].map(optionalDecimal);
  if (optional.some(entry => entry === null)) {
    return null;
  }
  return {
    starLevelId: values.starLevelId,
    salePrice,
    weightG,
    packagingTierId: values.packagingTierId ?? null,
    packagingCommission: optional[0] ?? undefined,
    boxLaborFee: optional[1] ?? undefined,
    transportPackingFee: optional[2] ?? undefined,
    moldAmortFee: optional[3] ?? undefined,
    seamTypeId: values.seamTypeId ?? null,
    clearSeamType: isSeamCleared(values),
    seamFee: optional[4] ?? undefined,
    refreshMaterialPrices: values.refreshMaterialPrices,
    refreshGlobalReferences: values.refreshGlobalReferences,
  };
}

/** 保存响应 → 预估面板：以服务端保存结果为准，比例展示文本按字符串位移生成。 */
export function previewFromDetail(detail: ProductDetail): ProductPreviewResult {
  return {
    glueGrams: detail.glueGrams,
    glueCost: detail.glueCost,
    colorpasteCost: detail.colorpasteCost,
    materialCost: detail.materialCost,
    productLaborFee: detail.productLaborFee,
    packagingLaborFee: detail.packagingLaborFee,
    boxLaborFee: detail.boxLaborFee,
    laborCost: detail.laborCost,
    otherCost: detail.otherCost,
    totalCost: detail.totalCost,
    referencePrice: detail.referencePrice,
    qty8h: detail.qty8h,
    qty6h: detail.qty6h,
    salePrice: detail.salePrice,
    estimatedProfit: detail.estimatedProfit,
    estimatedMarginRate: detail.estimatedMarginRate,
    estimatedMarginRatePercent: formatRatioPercent(detail.estimatedMarginRate),
    seamBudget: detail.seamBudget ?? null,
  };
}

/** 比例文本 → 百分比展示文本：纯字符串位移，不做浮点运算（如 0.351200 → 35.12%）。 */
export function formatRatioPercent(ratio: string): string {
  const text = ratio.trim();
  const negative = text.startsWith('-');
  const unsigned = negative ? text.slice(1) : text;
  const [integerPart = '0', fractionPart = ''] = unsigned.split('.');
  const digits = integerPart + fractionPart;
  const pointIndex = integerPart.length + 2;
  const padded = digits.padStart(pointIndex + 1, '0');
  const shiftedInteger = padded.slice(0, pointIndex).replace(/^0+(?=\d)/, '');
  const shiftedFraction = padded.slice(pointIndex).replace(/0+$/, '');
  const value = shiftedFraction ? `${shiftedInteger}.${shiftedFraction}` : shiftedInteger;
  return `${negative ? '-' : ''}${value}%`;
}
