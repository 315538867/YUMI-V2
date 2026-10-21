package com.yumi.product.api;

import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.LossRate;
import java.util.List;

/**
 * 商品录入默认值、制作星级与工序标准时间选项。
 *
 * <p>规则来源：`docs/一期/09-商品模块.md` §9.1、§14：这些配置只用于录入时带出建议值；
 * 商品提交后保存最终字段，默认值变化不刷新既有商品，也不生成快照。
 */
public record ProductEntryConfigurationView(
        LossRate glueLossRate,
        CostAmount glueUnitPrice,
        CostAmount colorPasteUnitPrice,
        long rowVersion,
        List<StarOption> makingStars,
        List<ProcessTimeOption> processTimeOptions) {

    public ProductEntryConfigurationView {
        makingStars = List.copyOf(makingStars);
        processTimeOptions = List.copyOf(processTimeOptions);
    }

    /** 制作星级选项：名称与标准分钟随商品保存，不动态引用。 */
    public record StarOption(String starCode, String name, int standardMinutes, int sortOrder) {
    }

    /** 工序标准时间选项。 */
    public record ProcessTimeOption(
            String optionBusinessId, String processCode, String optionCode, String name,
            int standardMinutes, int sortOrder) {
    }
}
