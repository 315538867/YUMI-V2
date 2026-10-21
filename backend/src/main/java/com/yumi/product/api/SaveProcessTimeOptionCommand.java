package com.yumi.product.api;

/** 保存工序标准时间选项。 */
public record SaveProcessTimeOptionCommand(
        String optionBusinessId,
        String processCode,
        String optionCode,
        String name,
        int standardMinutes,
        int sortOrder,
        boolean active,
        long expectedRowVersion) {
}
