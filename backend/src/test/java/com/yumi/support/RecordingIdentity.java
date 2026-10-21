package com.yumi.support;

import com.yumi.platform.api.identity.CurrentActor;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.value.StableBusinessId;
import java.util.Set;

/** 当前操作者测试替身：固定账号，用于业务模块单元/集成测试。 */
public final class RecordingIdentity implements CurrentActorProvider {

    private final CurrentActor actor;

    public RecordingIdentity(String accountBusinessId) {
        this.actor = new CurrentActor(
                StableBusinessId.of(accountBusinessId),
                "test-operator",
                StableBusinessId.of("SES-TEST"),
                Set.of());
    }

    public RecordingIdentity(CurrentActor actor) {
        this.actor = actor;
    }

    @Override
    public CurrentActor requireCurrentActor() {
        return actor;
    }
}
