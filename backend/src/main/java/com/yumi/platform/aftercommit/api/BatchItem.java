package com.yumi.platform.aftercommit.api;

import com.yumi.platform.api.value.ContentFingerprint;

/** 批次内单个来源项。 */
public record BatchItem<T>(int index, T payload, ContentFingerprint itemFingerprint) {
}
