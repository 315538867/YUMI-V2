package com.yumi.platform.api.file;

/**
 * 创建临时上传命令。
 *
 * @param content 文件内容；大小与类型在此校验，超限直接拒绝
 */
public record CreateUploadCommand(String originalName, String mediaType, byte[] content) {
}
