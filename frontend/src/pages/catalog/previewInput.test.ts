import { describe, expect, it } from 'vitest';
import type { ProductDetail } from '../../api/catalog';
import { collectPreviewInput, formatRatioPercent, previewFromDetail } from './previewInput';

const COMPLETE = {
  starLevelId: 3,
  salePrice: '25.0000',
  weightG: 270,
  packagingTierId: 7,
  packagingCommission: '0.3000',
  seamTypeId: 11,
  seamFee: '2.0000',
  boxLaborFee: '0.5000',
  transportPackingFee: '0.3000',
  moldAmortFee: '0.1000',
};

describe('试算输入收集（任务 2.17 / 2.24）', () => {
  it('计算字段齐备时原样传递字符串金额，不丢精度', () => {
    expect(collectPreviewInput(COMPLETE)).toEqual({
      starLevelId: 3,
      salePrice: '25.0000',
      weightG: 270,
      packagingTierId: 7,
      packagingCommission: '0.3000',
      seamTypeId: 11,
      clearSeamType: false,
      seamFee: '2.0000',
      boxLaborFee: '0.5000',
      transportPackingFee: '0.3000',
      moldAmortFee: '0.1000',
      refreshMaterialPrices: undefined,
      refreshGlobalReferences: undefined,
    });
  });

  it('星级或克重缺失时不请求', () => {
    expect(collectPreviewInput({ ...COMPLETE, starLevelId: undefined })).toBeNull();
    expect(collectPreviewInput({ ...COMPLETE, starLevelId: null })).toBeNull();
    expect(collectPreviewInput({ ...COMPLETE, weightG: undefined })).toBeNull();
    expect(collectPreviewInput({ ...COMPLETE, weightG: 1.5 })).toBeNull();
    expect(collectPreviewInput({ ...COMPLETE, weightG: -1 })).toBeNull();
  });

  it('必填金额为空或非法文本时不请求（非法文本由字段规则提示）', () => {
    expect(collectPreviewInput({ ...COMPLETE, salePrice: '' })).toBeNull();
    expect(collectPreviewInput({ ...COMPLETE, salePrice: 'abc' })).toBeNull();
    expect(collectPreviewInput({ ...COMPLETE, packagingCommission: '0.3.0' })).toBeNull();
  });

  it('可选费用留空按缺省处理，非法文本时不请求', () => {
    const blank = collectPreviewInput({ ...COMPLETE, boxLaborFee: '', moldAmortFee: undefined });
    expect(blank?.boxLaborFee).toBeUndefined();
    expect(blank?.moldAmortFee).toBeUndefined();
    expect(collectPreviewInput({ ...COMPLETE, boxLaborFee: 'x' })).toBeNull();
  });

  it('无包装档位时传 null，保持 PATCH 缺省合并语义', () => {
    expect(collectPreviewInput({ ...COMPLETE, packagingTierId: null })?.packagingTierId).toBeNull();
    expect(collectPreviewInput({ ...COMPLETE, packagingTierId: undefined })?.packagingTierId).toBeNull();
  });

  it('默认缝边剪袋类型留空时传 null（默认不缝边剪袋），缝边价格非法时不请求', () => {
    expect(collectPreviewInput({ ...COMPLETE, seamTypeId: null })?.seamTypeId).toBeNull();
    expect(collectPreviewInput({ ...COMPLETE, seamTypeId: undefined })?.seamTypeId).toBeNull();
    expect(collectPreviewInput({ ...COMPLETE, seamFee: '' })?.seamFee).toBeUndefined();
    expect(collectPreviewInput({ ...COMPLETE, seamFee: 'x' })).toBeNull();
    // 缝边价格是订单默认值，不影响商品自身成本，缺省不影响试算
    expect(collectPreviewInput({ ...COMPLETE, seamFee: undefined })?.seamFee).toBeUndefined();
  });

  it('清空默认缝边剪袋类型时显式带 clearSeamType，选择种类时不带', () => {
    // 只传 null 会被 PATCH 缺省合并理解成“保持原值”，必须显式声明清空
    expect(collectPreviewInput({ ...COMPLETE, seamTypeId: null })?.clearSeamType).toBe(true);
    expect(collectPreviewInput({ ...COMPLETE, seamTypeId: undefined })?.clearSeamType).toBe(true);
    expect(collectPreviewInput({ ...COMPLETE, seamTypeId: 11 })?.clearSeamType).toBe(false);
  });

  it('保存响应映射为预估面板，比例展示文本由字符串位移生成', () => {
    const detail = {
      glueGrams: 324,
      glueCost: '3.2400',
      colorpasteCost: '6.4800',
      materialCost: '9.7200',
      productLaborFee: '5.0000',
      packagingLaborFee: '0.0000',
      boxLaborFee: '0.5000',
      laborCost: '5.5000',
      otherCost: '1.0000',
      totalCost: '16.2200',
      referencePrice: '23.1714',
      qty8h: 32,
      qty6h: 24,
      salePrice: '25.0000',
      estimatedProfit: '8.7800',
      estimatedMarginRate: '0.351200',
      seamBudget: {
        seamUnitCost: '1.2500',
        seamFee: '2.0000',
        totalCost: '17.4700',
        referencePrice: '24.9571',
      },
    } as unknown as ProductDetail;

    const preview = previewFromDetail(detail);
    expect(preview.totalCost).toBe('16.2200');
    expect(preview.estimatedMarginRate).toBe('0.351200');
    expect(preview.estimatedMarginRatePercent).toBe('35.12%');
    expect(preview.seamBudget?.totalCost).toBe('17.4700');
  });

  it('未选默认缝边剪袋类型时缝边剪袋变体为 null，面板不展示数值', () => {
    const preview = previewFromDetail({
      estimatedMarginRate: '0.000000',
      seamBudget: null,
    } as unknown as ProductDetail);
    expect(preview.seamBudget).toBeNull();
  });
});

describe('比例展示文本（任务 2.17）', () => {
  it('按字符串位移换算百分比并去掉无意义尾零', () => {
    expect(formatRatioPercent('0.351200')).toBe('35.12%');
    expect(formatRatioPercent('0.666667')).toBe('66.6667%');
    expect(formatRatioPercent('1.000000')).toBe('100%');
    expect(formatRatioPercent('0.100000')).toBe('10%');
    expect(formatRatioPercent('0.005000')).toBe('0.5%');
    expect(formatRatioPercent('0.000000')).toBe('0%');
    expect(formatRatioPercent('-0.152200')).toBe('-15.22%');
  });
});
