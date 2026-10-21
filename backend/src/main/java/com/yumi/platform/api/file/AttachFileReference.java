package com.yumi.platform.api.file;

/**
 * 业务引用文件：加入调用方事务。
 *
 * @param ownerModule 引用方模块代码
 * @param ownerType 引用方对象类型
 * @param ownerBusinessId 引用方对象稳定业务标识
 * @param purposeCode 用途代码，例如 {@code CASH_VOUCHER}
 */
public record AttachFileReference(
        String fileBusinessId,
        String ownerModule,
        String ownerType,
        String ownerBusinessId,
        String purposeCode) {
}
