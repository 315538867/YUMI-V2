package com.yumi.support;

import com.yumi.platform.api.numbering.BusinessNumberService;
import java.util.concurrent.atomic.AtomicInteger;

/** 业务编号测试替身：按前缀生成可预测编号。 */
public final class RecordingNumbering implements BusinessNumberService {

    private final String prefix;

    private final AtomicInteger counter = new AtomicInteger();

    public RecordingNumbering(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public String nextNumber(String sequenceCode, String prefix, int width) {
        return (prefix == null || prefix.isBlank() ? this.prefix : prefix)
                + String.format("%0" + width + "d", counter.incrementAndGet());
    }
}
