package com.yumi.product.api;

import com.yumi.platform.api.value.ContentFingerprint;
import java.util.List;
import java.util.Optional;

/**
 * 订单生效生产依据解析（基线 09 §7.4）。
 *
 * <p>商品方案是模板；订单最终供料约定可能不同。本查询把“历史方案快照 + 最终目标工序 +
 * 完整材料来源约定”解析为类型化不可变依据，不修改共享方案，也不接受客户端伪造的金额。
 */
public interface ProductCostBasisQuery {

    /**
     * 解析订单生效生产依据。
     *
     * @param schemeSnapshotRef 订单引用的方案快照
     * @param finalTargetProcess 订单最终目标工序；为空表示沿用快照
     * @param materialSources 完整材料来源约定；为空表示沿用快照
     */
    ResolvedProductionBasis resolveProductionBasis(
            ProductSnapshotRef schemeSnapshotRef,
            SchemeView.ProcessCode finalTargetProcess,
            MaterialSourceAgreement materialSources);

    /**
     * 只提取当前工序、工作室承担的非人工成本行，用于材料成本归集。
     *
     * <p>不得退回按原方案默认供料方读取；依据缺失或指纹不一致必须明确失败。
     */
    ProductionProcessCostBasis resolveProductionProcessCostBasis(
            ResolvedProductionBasis basis, SchemeView.ProcessCode currentProcess);

    /**
     * 完整参考分项：材料、人工与预计分摊，供工资逐项参考。
     *
     * <p>不套用材料入账的非人工过滤，不返回实际扣款或当前商品价格。
     */
    List<ReferenceCostLine> resolveReferenceCostLines(ResolvedProductionBasis basis);

    /** 材料来源约定：必须显式给出胶水、色浆、袋子与装饰品四项。 */
    record MaterialSourceAgreement(
            MaterialSource glue,
            MaterialSource colorPaste,
            MaterialSource bag,
            MaterialSource decoration) {

        public MaterialSourceAgreement {
            if (glue == null || colorPaste == null || bag == null || decoration == null) {
                throw new IllegalArgumentException("材料来源约定必须完整给出，不能用空值表示默认供料方");
            }
        }
    }

    /** 解析后的不可变生产依据。 */
    record ResolvedProductionBasis(
            ProductSnapshotRef schemeSnapshotRef,
            ProductSnapshotRef productSnapshotRef,
            String schemeBusinessId,
            SchemeView.ProcessCode targetProcess,
            MaterialSourceAgreement materialSources,
            String formulaRuleVersion,
            String roundingVersion,
            ContentFingerprint contentFingerprint,
            List<BasisCostLine> costLines,
            int makingStandardMinutes,
            int baggingStandardMinutes,
            int seamStandardMinutes) {

        public ResolvedProductionBasis {
            costLines = List.copyOf(costLines);
        }
    }

    /**
     * 依据中的单条成本行。
     *
     * @param componentCode 稳定成本项标识
     * @param processCode 归属工序；额外单件费用为空
     * @param nature 成本性质
     * @param source 供料方
     * @param fullResourceCost 历史单件全资源标准价值
     */
    record BasisCostLine(
            String componentCode,
            String processCode,
            String nature,
            MaterialSource source,
            java.math.BigDecimal quantity,
            com.yumi.platform.api.value.CostAmount unitPrice,
            com.yumi.platform.api.value.CostAmount fullResourceCost) {

        public BasisCostLine(
                String componentCode, String processCode, String nature, MaterialSource source,
                com.yumi.platform.api.value.CostAmount fullResourceCost) {
            this(componentCode, processCode, nature, source, null, null, fullResourceCost);
        }
    }

    /** 当前工序的工作室非人工成本依据。 */
    record ProductionProcessCostBasis(
            String schemeBusinessId,
            SchemeView.ProcessCode currentProcess,
            ContentFingerprint basisFingerprint,
            List<BasisCostLine> studioNonLaborLines,
            com.yumi.platform.api.value.CostAmount totalStudioNonLaborCost) {

        public ProductionProcessCostBasis {
            studioNonLaborLines = List.copyOf(studioNonLaborLines);
        }
    }

    /**
     * 参考成本分项（工资逐项参考）。
     *
     * @param missingReason 历史依据缺失原因；为空表示完整
     */
    record ReferenceCostLine(
            String componentCode,
            String processCode,
            String nature,
            MaterialSource source,
            Optional<com.yumi.platform.api.value.CostAmount> standardValue,
            String missingReason) {
    }
}
