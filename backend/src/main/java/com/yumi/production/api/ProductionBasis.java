package com.yumi.production.api;

import com.yumi.platform.api.value.ContentFingerprint;
import java.util.List;

/**
 * 冻结的生产依据（订单生效生产依据的生产侧副本）。
 *
 * <p>由商品模块解析后传入并在订单确认事务中保存；后续任务安排只按本依据解析工时与人工标准。
 *
 * @param targetProcess 依据中的目标工序
 * @param materialSources 逐项材料来源
 * @param costLines 成本行（含承担方与历史单件标准）
 * @param fingerprint 依据指纹；与订单保存的一致
 */
public record ProductionBasis(
        String schemeSnapshotRef,
        String productSnapshotRef,
        String schemeBusinessId,
        TargetProcess targetProcess,
        MaterialSources materialSources,
        List<CostLine> costLines,
        String formulaRuleVersion,
        String roundingVersion,
        ContentFingerprint fingerprint,
        int makingStandardMinutes,
        int baggingStandardMinutes,
        int seamStandardMinutes) {

    public ProductionBasis {
        costLines = List.copyOf(costLines);
    }

    /** 逐项材料来源：必须完整，不能用空值表示默认供料方。 */
    public record MaterialSources(
            com.yumi.product.api.MaterialSource glue,
            com.yumi.product.api.MaterialSource colorPaste,
            com.yumi.product.api.MaterialSource bag,
            com.yumi.product.api.MaterialSource decoration) {

        public MaterialSources {
            if (glue == null || colorPaste == null || bag == null || decoration == null) {
                throw new IllegalArgumentException("材料来源必须完整给出，不能用空值表示默认供料方");
            }
        }
    }

    /** 依据中的成本行。 */
    public record CostLine(
            String componentCode,
            TargetProcess processCode,
            String nature,
            com.yumi.product.api.MaterialSource source,
            java.math.BigDecimal quantity,
            com.yumi.platform.api.value.CostAmount unitPrice,
            com.yumi.platform.api.value.CostAmount fullResourceCost) {

        public CostLine(
                String componentCode, TargetProcess processCode, String nature,
                com.yumi.product.api.MaterialSource source,
                com.yumi.platform.api.value.CostAmount fullResourceCost) {
            this(componentCode, processCode, nature, source, null, null, fullResourceCost);
        }
    }
}
