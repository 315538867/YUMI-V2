package com.yumi.platform.api.file;

import com.yumi.platform.api.value.StableBusinessId;

/** 已正式化的文件引用。 */
public record FormalFileRef(StableBusinessId fileBusinessId, String mediaType, String sha256) {
}
