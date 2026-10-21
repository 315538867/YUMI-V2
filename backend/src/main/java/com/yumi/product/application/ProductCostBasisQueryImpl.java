package com.yumi.product.application;

import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.value.ContentFingerprint;
import com.yumi.product.api.MaterialSource;
import com.yumi.product.api.ProductCostBasisQuery;
import com.yumi.product.api.ProductSnapshotRef;
import com.yumi.product.api.ProductSnapshotService;
import com.yumi.product.api.SchemeSnapshotView;
import com.yumi.product.api.SchemeView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 订单生效生产依据解析（基线 09 §7.4）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>输入只允许覆盖订单已开放的目标工序与材料来源；工时、人工与材料单价一律来自历史快照；</li>
 *   <li>模板默认供料方在解析前补全，但必须由调用方显式给出，不能以空值表示隐含来源；</li>
 *   <li>返回不可变依据与稳定指纹；订单保存该依据，生产原样保存，成本 Feed 原样公开；</li>
 *   <li>{@code resolveProductionProcessCostBasis} 只取当前工序、工作室承担的非人工行，
 *       不回退按方案默认供料方读取；</li>
 *   <li>{@code resolveReferenceCostLines} 返回材料、人工与预计分摊的完整分项，供工资逐项参考。</li>
 * </ul>
 */
public class ProductCostBasisQueryImpl implements ProductCostBasisQuery {

    private final ProductSnapshotService snapshots;

    private final AuthorizationService authorization;

    public ProductCostBasisQueryImpl(
            ProductSnapshotService snapshots, AuthorizationService authorization) {
        this.snapshots = snapshots;
        this.authorization = authorization;
    }

    @Override
    public ResolvedProductionBasis resolveProductionBasis(
            ProductSnapshotRef schemeSnapshotRef,
            SchemeView.ProcessCode finalTargetProcess,
            MaterialSourceAgreement materialSources) {
        authorization.require(PermissionCodes.PRODUCT_SNAPSHOT_READ, ResourceScope.GLOBAL);
        if (schemeSnapshotRef == null) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCT_SNAPSHOT_INVALID", "订单必须引用方案快照");
        }

        SchemeSnapshotView snapshot = snapshots.resolveScheme(schemeSnapshotRef);
        SchemeView.ProcessCode targetProcess =
                finalTargetProcess == null ? snapshot.targetProcess() : finalTargetProcess;
        MaterialSourceAgreement sources = materialSources == null
                ? new MaterialSourceAgreement(
                        snapshot.glueSource(), snapshot.colorPasteSource(),
                        MaterialSource.valueOf("STUDIO"), MaterialSource.valueOf("STUDIO"))
                : materialSources;

        List<BasisCostLine> lines = new ArrayList<>();
        SchemeView.SchemeBudgetView budget = snapshot.budget();

        // 从快照预算明细解析；供料方按订单最终约定覆盖材料行
        for (SchemeView.BudgetLineView line : budget.lines()) {
            MaterialSource source = resolveSource(line.componentCode(), sources);
            lines.add(new BasisCostLine(
                    line.componentCode(),
                    line.processCode(),
                    line.costNature(),
                    source,
                    line.quantity(),
                    line.unitPrice(),
                    line.fullResourceCost()));
        }
        if (lines.isEmpty()) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "PRODUCT_BUDGET_CALCULATION_FAILED",
                    "方案快照缺少预算明细，无法解析生产依据");
        }

        ContentFingerprint fingerprint = ContentFingerprint.of(sha256(
                snapshot.ref().snapshotBusinessId() + "|" + targetProcess + "|"
                        + sources.glue() + "|" + sources.colorPaste() + "|"
                        + sources.bag() + "|" + sources.decoration() + "|"
                        + budget.inputFingerprint()));

        return new ResolvedProductionBasis(
                snapshot.ref(),
                snapshot.productSnapshotRef(),
                snapshot.schemeBusinessId(),
                targetProcess,
                sources,
                budget.formulaRuleVersion(),
                budget.roundingVersion(),
                fingerprint,
                lines,
                snapshot.makingStandardMinutes(),
                snapshot.baggingStandardMinutes(),
                snapshot.seamStandardMinutes());
    }

    @Override
    public ProductionProcessCostBasis resolveProductionProcessCostBasis(
            ResolvedProductionBasis basis, SchemeView.ProcessCode currentProcess) {
        authorization.require(PermissionCodes.PRODUCT_SNAPSHOT_READ, ResourceScope.GLOBAL);
        if (basis == null) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCT_BASIS_INVALID", "生产依据不能为空");
        }
        if (currentProcess == null) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCT_TARGET_PROCESS_INVALID", "当前工序不能为空");
        }
        if (!basis.costLines().stream().anyMatch(line -> currentProcess.name().equals(line.processCode())
                || line.processCode() == null)) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "PRODUCT_BASIS_INVALID",
                    "生产依据与当前工序不匹配，不能以默认方案或零成本兜底");
        }

        List<BasisCostLine> studioNonLabor = basis.costLines().stream()
                .filter(line -> currentProcess.name().equals(line.processCode()))
                .filter(line -> line.source() == MaterialSource.STUDIO)
                .filter(line -> !"LABOR".equals(line.nature()))
                .toList();

        com.yumi.platform.api.value.CostAmount total = studioNonLabor.stream()
                .map(BasisCostLine::fullResourceCost)
                .reduce(
                        com.yumi.platform.api.value.CostAmount.of(
                                java.math.BigDecimal.ZERO, com.yumi.platform.api.value.CurrencyCode.CNY),
                        com.yumi.platform.api.value.CostAmount::plus);

        return new ProductionProcessCostBasis(
                basis.schemeBusinessId(), currentProcess, basis.contentFingerprint(), studioNonLabor, total);
    }

    @Override
    public List<ReferenceCostLine> resolveReferenceCostLines(ResolvedProductionBasis basis) {
        authorization.require(PermissionCodes.PRODUCT_SNAPSHOT_READ, ResourceScope.GLOBAL);
        if (basis == null) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCT_BASIS_INVALID", "生产依据不能为空");
        }
        return basis.costLines().stream()
                .map(line -> new ReferenceCostLine(
                        line.componentCode(),
                        line.processCode(),
                        line.nature(),
                        line.source(),
                        Optional.of(line.fullResourceCost()),
                        null))
                .toList();
    }

    private static MaterialSource resolveSource(String componentCode, MaterialSourceAgreement sources) {
        return switch (componentCode) {
            case "GLUE" -> sources.glue();
            case "COLOR_PASTE" -> sources.colorPaste();
            case "BAGGING_BAG" -> sources.bag();
            case "BAGGING_DECORATION" -> sources.decoration();
            // 人工、提成、额外单件费用固定工作室承担；缝边剪袋无客户供料选项
            default -> MaterialSource.STUDIO;
        };
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法", exception);
        }
    }
}
