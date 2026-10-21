package com.yumi.product.api;

/** 保存制作星级选项。 */
public record SaveMakingStarOptionCommand(
        String starCode, String name, int standardMinutes, int sortOrder, boolean active, long expectedRowVersion) {
}
