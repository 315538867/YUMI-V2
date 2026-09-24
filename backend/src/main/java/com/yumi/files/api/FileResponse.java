package com.yumi.files.api;

/**
 * 上传成功返回体；同 sha256 重复上传幂等地返回既有记录。
 */
public record FileResponse(long fileId, String objectKey, String contentType, long size, String sha256) {
}
