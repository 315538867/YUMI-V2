package com.yumi.catalog.product.internal;

import com.yumi.calculation.DecimalPolicy;
import com.yumi.calculation.product.ProductPricing;
import com.yumi.catalog.product.CreateProductRequest;
import com.yumi.catalog.product.UpdateProductRequest;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 商品计算输入解析（任务 2.15，2026-09-24 按字段归属修订）：新建、编辑与试算共用的唯一解析入口。
 * 只负责引用选择、快照规则与 PATCH 缺省合并；不含成本公式，也不校验名称、图片等非计算资料。
 * 字段归属：商品属性由商品提交；全局口径字段（胶水损耗率、日常杂费、房租水电）一律取当前全局设置；
 * 全局默认费用（装箱人工费、运输包装费）允许商品覆盖；星级与包装档位从静态数据选择并冻结快照。
 * 缝边只解析默认值（默认缝边剪袋类型 + 缝边价格）供订单缝边定制带出，不含缝边数量与缝边成本。
 */
@Component
public class ProductInputResolver {

    private final CatalogReference reference;

    public ProductInputResolver(CatalogReference reference) {
        this.reference = reference;
    }

    /** 解析结果：集中公式输入，外加需要冻结进商品快照的引用、商品包装提成与缝边默认值。 */
    public record Resolved(
            ProductPricing.Inputs inputs,
            long starLevelId,
            String starName,
            Long packagingTierId,
            String packagingTierName,
            BigDecimal packagingCommission,
            Long seamTypeId,
            String seamTypeName,
            Integer seamStdMinutes,
            BigDecimal seamUnitCost,
            BigDecimal seamFee) {
    }

    /**
     * 新建：商品属性取请求，全局口径字段取当前全局设置，四项费用未传取全局默认，模具摊销未传=0；
     * 默认缝边剪袋类型未传＝默认不缝边剪袋，缝边价格未传=0。
     * 计算字段非法或引用不存在时，连同调用方已收集的错误一起抛出聚合的 400。
     */
    public Resolved forCreate(CreateProductRequest request, List<ApiFieldError> errors) {
        CatalogReference.StarLevel starLevel = null;
        if (request.starLevelId() == null) {
            errors.add(new ApiFieldError("starLevelId", "星级必填"));
        } else {
            starLevel = reference.starLevelById(request.starLevelId()).orElse(null);
            if (starLevel == null) {
                errors.add(new ApiFieldError("starLevelId", "星级条目不存在"));
            }
        }
        requiredNonNegative(request.salePrice(), "salePrice", errors);
        if (request.weightG() == null) {
            errors.add(new ApiFieldError("weightG", "重量必填"));
        } else if (request.weightG() < 0) {
            errors.add(new ApiFieldError("weightG", "重量不能为负数"));
        }
        optionalNonNegative(request.packagingCommission(), "packagingCommission", errors);
        optionalNonNegative(request.boxLaborFee(), "boxLaborFee", errors);
        optionalNonNegative(request.transportPackingFee(), "transportPackingFee", errors);
        optionalNonNegative(request.moldAmortFee(), "moldAmortFee", errors);
        optionalNonNegative(request.seamFee(), "seamFee", errors);
        if (request.packagingTierId() != null && reference.packagingTier(request.packagingTierId()).isEmpty()) {
            errors.add(new ApiFieldError("packagingTierId", "包装档位不存在"));
        }
        if (request.seamTypeId() != null && reference.seamType(request.seamTypeId()).isEmpty()) {
            errors.add(new ApiFieldError("seamTypeId", "缝边种类不存在"));
        }
        failIfInvalid(errors);

        var tier = reference.packagingTier(request.packagingTierId()).orElse(null);
        var seamType = reference.seamType(request.seamTypeId()).orElse(null);
        BigDecimal gluePrice = reference.moneySetting("glue_unit_price");
        BigDecimal colorpastePrice = reference.moneySetting("colorpaste_unit_price");
        BigDecimal box = request.boxLaborFee() != null
                ? DecimalPolicy.money(request.boxLaborFee()) : reference.moneySetting("box_labor_default");
        BigDecimal transport = request.transportPackingFee() != null
                ? DecimalPolicy.money(request.transportPackingFee())
                : reference.moneySetting("transport_packing_default");
        BigDecimal mold = request.moldAmortFee() != null
                ? DecimalPolicy.money(request.moldAmortFee()) : DecimalPolicy.money(BigDecimal.ZERO);
        // 包装提成：商品未填时取全局默认（可在商品上修改）
        BigDecimal commission = request.packagingCommission() != null
                ? DecimalPolicy.money(request.packagingCommission())
                : reference.moneySetting("packaging_commission_default");
        // 缝边价格：商品未填时按 0（默认不缝边剪袋时无收费口径）
        BigDecimal seamFee = request.seamFee() != null
                ? DecimalPolicy.money(request.seamFee()) : DecimalPolicy.money(BigDecimal.ZERO);

        var inputs = new ProductPricing.Inputs(request.weightG(), globalLossRate(), starLevel.stdMinutes(),
                tier == null ? null : tier.stdMinutes(), commission,
                reference.hourlyWage(), reference.workdayHours(), reference.makingEffectiveHourRate(),
                gluePrice, colorpastePrice, box, transport,
                reference.moneySetting("sundries_default"), reference.moneySetting("rent_utilities_default"),
                mold, DecimalPolicy.money(request.salePrice()));
        return new Resolved(inputs, starLevel.id(), starLevel.name(),
                tier == null ? null : tier.id(), tier == null ? null : tier.tierName(), commission,
                seamType == null ? null : seamType.id(), seamType == null ? null : seamType.name(),
                seamType == null ? null : seamType.stdMinutes(), seamUnitCost(seamType), seamFee);
    }

    /**
     * 编辑：PATCH 缺省合并。星级/档位仅在换成不同引用或显式请求刷新时读当前配置，否则保留商品快照；
     * 材料单价仅在 refreshMaterialPrices 或 refreshGlobalReferences 时重读全局；
     * 全局口径字段一律取当前全局设置；默认缝边剪袋类型未传保持原值、clearSeamType=true 才清空。
     * 服务端整体重算，不采信客户端派生金额。
     */
    public Resolved forUpdate(UpdateProductRequest request, ProductRow existing, List<ApiFieldError> errors) {
        long starLevelId = request.starLevelId() != null ? request.starLevelId() : existing.starLevelId();
        optionalNonNegative(request.salePrice(), "salePrice", errors);
        if (request.weightG() != null && request.weightG() < 0) {
            errors.add(new ApiFieldError("weightG", "重量不能为负数"));
        }
        optionalNonNegative(request.packagingCommission(), "packagingCommission", errors);
        optionalNonNegative(request.boxLaborFee(), "boxLaborFee", errors);
        optionalNonNegative(request.transportPackingFee(), "transportPackingFee", errors);
        optionalNonNegative(request.moldAmortFee(), "moldAmortFee", errors);
        optionalNonNegative(request.seamFee(), "seamFee", errors);
        boolean refreshGlobals = Boolean.TRUE.equals(request.refreshGlobalReferences());
        boolean clearSeam = Boolean.TRUE.equals(request.clearSeamType());
        if (clearSeam && request.seamTypeId() != null) {
            errors.add(new ApiFieldError("clearSeamType", "不能同时选择与清空默认缝边剪袋类型"));
        }
        // 仅“重新选择星级”或显式刷新时按当前全局取值；未重选、选同一引用继续用商品自身快照
        var starChanged = request.starLevelId() != null && !request.starLevelId().equals(existing.starLevelId());
        var starFromGlobal = starChanged || refreshGlobals
                ? reference.starLevelById(starLevelId).orElse(null)
                : null;
        var starLevel = starFromGlobal != null
                ? starFromGlobal
                : starChanged ? null
                        : new CatalogReference.StarLevel(starLevelId, existing.starName(), existing.starStdMinutes());
        if (starLevel == null) {
            errors.add(new ApiFieldError("starLevelId", "星级条目不存在"));
        }
        if (request.packagingTierId() != null && reference.packagingTier(request.packagingTierId()).isEmpty()) {
            errors.add(new ApiFieldError("packagingTierId", "包装档位不存在"));
        }
        if (request.seamTypeId() != null && reference.seamType(request.seamTypeId()).isEmpty()) {
            errors.add(new ApiFieldError("seamTypeId", "缝边种类不存在"));
        }
        failIfInvalid(errors);

        // 档位：换成不同档位或显式刷新时读当前参考数据，同档位/未传继续用原快照
        final CatalogReference.PackagingTier tier;
        if (request.packagingTierId() != null
                && !Objects.equals(request.packagingTierId(), existing.packagingTierId())) {
            tier = reference.packagingTier(request.packagingTierId()).orElse(null);
        } else if (existing.packagingTierId() == null) {
            tier = null;
        } else {
            var current = refreshGlobals
                    ? reference.packagingTier(existing.packagingTierId()).orElse(null)
                    : null;
            tier = current != null ? current
                    : new CatalogReference.PackagingTier(existing.packagingTierId(), existing.packagingTierName(),
                            existing.packagingStdMinutes());
        }
        boolean refresh = refreshGlobals || Boolean.TRUE.equals(request.refreshMaterialPrices());
        BigDecimal gluePrice = refresh ? reference.moneySetting("glue_unit_price") : existing.glueUnitPrice();
        BigDecimal colorpastePrice = refresh
                ? reference.moneySetting("colorpaste_unit_price") : existing.colorpasteUnitPrice();
        BigDecimal sale = request.salePrice() != null
                ? DecimalPolicy.money(request.salePrice()) : existing.salePrice();
        int weightG = request.weightG() != null ? request.weightG() : existing.weightG();
        BigDecimal box = request.boxLaborFee() != null
                ? DecimalPolicy.money(request.boxLaborFee()) : existing.boxLaborFee();
        BigDecimal transport = request.transportPackingFee() != null
                ? DecimalPolicy.money(request.transportPackingFee()) : existing.transportPackingFee();
        BigDecimal mold = request.moldAmortFee() != null
                ? DecimalPolicy.money(request.moldAmortFee()) : existing.moldAmortFee();
        BigDecimal commission = request.packagingCommission() != null
                ? DecimalPolicy.money(request.packagingCommission()) : existing.packagingCommission();

        // 缝边默认值：清空 → 默认不缝边剪袋；换成不同引用或显式刷新时按当前时薪重新派生；
        // 同引用且未刷新时保留商品快照（名称与单件缝边人工成本都不回溯）
        final Long seamTypeId;
        final String seamTypeName;
        final Integer seamStdMinutes;
        final BigDecimal seamUnitCost;
        if (clearSeam) {
            seamTypeId = null;
            seamTypeName = null;
            seamStdMinutes = null;
            seamUnitCost = DecimalPolicy.money(BigDecimal.ZERO);
        } else if (request.seamTypeId() != null
                && !Objects.equals(request.seamTypeId(), existing.seamTypeId())) {
            var current = reference.seamType(request.seamTypeId()).orElse(null);
            seamTypeId = current == null ? null : current.id();
            seamTypeName = current == null ? null : current.name();
            seamStdMinutes = current == null ? null : current.stdMinutes();
            seamUnitCost = seamUnitCost(current);
        } else if (existing.seamTypeId() == null) {
            seamTypeId = null;
            seamTypeName = null;
            seamStdMinutes = null;
            seamUnitCost = DecimalPolicy.money(BigDecimal.ZERO);
        } else if (refreshGlobals) {
            var current = reference.seamType(existing.seamTypeId()).orElse(null);
            seamTypeId = current == null ? existing.seamTypeId() : current.id();
            seamTypeName = current == null ? existing.seamTypeName() : current.name();
            seamStdMinutes = current == null ? existing.seamStdMinutes() : current.stdMinutes();
            seamUnitCost = current == null ? existing.seamUnitCost() : seamUnitCost(current);
        } else {
            seamTypeId = existing.seamTypeId();
            seamTypeName = existing.seamTypeName();
            seamStdMinutes = existing.seamStdMinutes();
            seamUnitCost = existing.seamUnitCost();
        }
        BigDecimal seamFee = request.seamFee() != null
                ? DecimalPolicy.money(request.seamFee()) : existing.seamFee();

        var inputs = new ProductPricing.Inputs(weightG, globalLossRate(), starLevel.stdMinutes(),
                tier == null ? null : tier.stdMinutes(), commission,
                reference.hourlyWage(), reference.workdayHours(), reference.makingEffectiveHourRate(),
                gluePrice, colorpastePrice, box, transport,
                reference.moneySetting("sundries_default"), reference.moneySetting("rent_utilities_default"),
                mold, sale);
        return new Resolved(inputs, starLevelId, starLevel.name(),
                tier == null ? null : tier.id(), tier == null ? null : tier.tierName(), commission,
                seamTypeId, seamTypeName, seamStdMinutes, seamUnitCost, seamFee);
    }

    /** 单件缝边人工成本 = 种类标准分钟 × 当前全局时薪 ÷ 60；默认不缝边剪袋（无种类）时按 0，列非空。 */
    private BigDecimal seamUnitCost(CatalogReference.SeamType seamType) {
        return seamType == null
                ? DecimalPolicy.money(BigDecimal.ZERO)
                : ProductPricing.seamUnitCost(seamType.stdMinutes(), reference.hourlyWage());
    }

    /** 全局口径字段：胶水损耗率一律取当前全局设置（百分比 → 内部比例）。 */
    private BigDecimal globalLossRate() {
        return DecimalPolicy.percentToRatio(reference.setting("loss_rate_default"));
    }

    private static void failIfInvalid(List<ApiFieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID,
                    ErrorCode.VALIDATION_INVALID.defaultMessage(), errors);
        }
    }

    private static void requiredNonNegative(BigDecimal value, String field, List<ApiFieldError> errors) {
        if (value == null) {
            errors.add(new ApiFieldError(field, field + " 必填"));
        } else if (value.signum() < 0) {
            errors.add(new ApiFieldError(field, field + " 不能为负数"));
        }
    }

    private static void optionalNonNegative(BigDecimal value, String field, List<ApiFieldError> errors) {
        if (value != null && value.signum() < 0) {
            errors.add(new ApiFieldError(field, field + " 不能为负数"));
        }
    }
}
