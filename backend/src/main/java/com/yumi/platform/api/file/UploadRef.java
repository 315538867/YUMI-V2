package com.yumi.platform.api.file;

import com.yumi.platform.api.value.StableBusinessId;

/**
 * 临时上传引用。
 *
 * <p>临时文件不能直接被业务记录引用：业务提交时必须先经
 * {@link FileReferenceParticipant#attach} 正式化。
 */
public record UploadRef(
        StableBusinessId fileBusinessId, String originalName, String mediaType, long sizeBytes) {
}
