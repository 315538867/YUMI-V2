package com.yumi.production.verification;

import java.util.List;

/** 生产核验读模型（阶段五）：核验事实 + 本次分流结果，便于页面与测试逐项核对。 */
public final class ProductionVerificationViews {

    private ProductionVerificationViews() {
    }

    /** 合格数量的分流去向：制作→捏毛装袋、捏毛装袋→缝边剪袋/可发货、缝边剪袋→可发货。 */
    public record FlowView(String node, int quantity) {
    }

    public record VerificationView(
            long id,
            long planId,
            String planNo,
            long orderItemId,
            String node,
            int planQuantity,
            int completedQuantity,
            int qualifiedQuantity,
            int reworkQuantity,
            int scrapQuantity,
            int incompleteQuantity,
            String verifyNote,
            String verifiedBy,
            List<FlowView> flows,
            Long incompleteReminderId) {
    }
}
