package com.yumi.platform.api.file;

/** 授权读取得到的文件内容。 */
public record FileContent(String originalName, String mediaType, byte[] content) {
}
