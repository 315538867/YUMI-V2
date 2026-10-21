package com.yumi.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.platform.aftercommit.api.BatchCompleteness;
import com.yumi.platform.aftercommit.api.BatchItem;
import com.yumi.platform.aftercommit.api.SourceCommandBatch;
import com.yumi.platform.aftercommit.api.SourceCursor;
import com.yumi.platform.api.value.ContentFingerprint;
import com.yumi.platform.api.value.StableBusinessId;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 来源命令批次（完整性）契约。
 *
 * <p>规则来源：`docs/一期/05-跨模块接口与事务规则.md` 第 6 节与本提案 SF-05。同一来源命令产生的
 * 反向（REVERSE）与替代（APPLY）项构成不可拆分批次；消费者只能按完整批次更新有效投影并推进
 * 水位，分页不得把半组更正发布为“完整”。
 *
 * <p>本测试只验证纯契约，不验证数据库发布、分页或重放；那些由 7.11—7.12、10.1—10.2 的真实
 * MySQL 集成测试覆盖。
 */
class CommandBatchContractTest {

    private static final String FEED = "production.labor";

    private static final ContentFingerprint FINGERPRINT = ContentFingerprint.of("c".repeat(64));

    @Test
    @DisplayName("完整批次要求条数、序号与声明一致")
    void completeBatchRequiresContiguousItems() {
        SourceCommandBatch<String> batch = SourceCommandBatch.complete(
                FEED,
                StableBusinessId.of("CMD-1"),
                7L,
                2,
                items("A", "B"),
                FINGERPRINT);

        assertThat(batch.completeness()).isEqualTo(BatchCompleteness.COMPLETE);
        assertThat(batch.isPublishable()).isTrue();
        assertThat(batch.items()).hasSize(2);
        assertThat(batch.declaredItemCount()).isEqualTo(2);
        assertThat(batch.cursor().feedCode()).isEqualTo(FEED);
        assertThat(batch.cursor().position()).isEqualTo(7L);
    }

    @Test
    @DisplayName("缺少批次中的任何一项都不得声明为完整")
    void incompleteBatchCannotBeDeclaredComplete() {
        assertThatThrownBy(() -> SourceCommandBatch.complete(
                FEED, StableBusinessId.of("CMD-1"), 7L, 3, items("A", "B"), FINGERPRINT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不完整");
    }

    @Test
    @DisplayName("重复或跳号的项序号同样视为不完整")
    void duplicateOrSkippedIndexIsIncomplete() {
        List<BatchItem<String>> duplicate = List.of(
                new BatchItem<>(0, "A", ContentFingerprint.of("d".repeat(64))),
                new BatchItem<>(0, "B", ContentFingerprint.of("d".repeat(64))));
        assertThatThrownBy(() -> SourceCommandBatch.complete(
                FEED, StableBusinessId.of("CMD-1"), 7L, 2, duplicate, FINGERPRINT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("序号");

        List<BatchItem<String>> skipped = List.of(
                new BatchItem<>(0, "A", ContentFingerprint.of("d".repeat(64))),
                new BatchItem<>(2, "B", ContentFingerprint.of("d".repeat(64))));
        assertThatThrownBy(() -> SourceCommandBatch.complete(
                FEED, StableBusinessId.of("CMD-1"), 7L, 2, skipped, FINGERPRINT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("序号");
    }

    @Test
    @DisplayName("分页读到半组更正时必须标记为不完整，消费者不能推进水位")
    void partialBatchIsNeverPublishable() {
        SourceCommandBatch<String> partial = SourceCommandBatch.partial(
                FEED, StableBusinessId.of("CMD-1"), 7L, 3, items("A"), FINGERPRINT);

        assertThat(partial.completeness()).isEqualTo(BatchCompleteness.INCOMPLETE);
        assertThat(partial.isPublishable()).isFalse();
        assertThat(partial.declaredItemCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("空批次被拒绝：更正命令必须带有明确的反向或替代项")
    void emptyBatchRejected() {
        assertThatThrownBy(() -> SourceCommandBatch.complete(
                FEED, StableBusinessId.of("CMD-1"), 7L, 0, List.of(), FINGERPRINT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("为空");
    }

    @Test
    @DisplayName("批次不可变：外部列表修改不影响已声明的来源项")
    void batchIsImmutable() {
        List<BatchItem<String>> mutable = new ArrayList<>(items("A", "B"));
        SourceCommandBatch<String> batch = SourceCommandBatch.complete(
                FEED, StableBusinessId.of("CMD-1"), 7L, 2, mutable, FINGERPRINT);

        mutable.clear();

        assertThat(batch.items()).hasSize(2);
        assertThatThrownBy(() -> batch.items().add(
                new BatchItem<>(2, "C", ContentFingerprint.of("e".repeat(64)))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("游标属于同一 Feed，跨 Feed 复用被拒绝")
    void cursorMustBelongToSameFeed() {
        SourceCommandBatch<String> batch = SourceCommandBatch.complete(
                FEED, StableBusinessId.of("CMD-1"), 7L, 1, items("A"), FINGERPRINT);
        assertThat(batch.cursor()).isEqualTo(new SourceCursor(FEED, 7L));

        assertThatThrownBy(() -> SourceCommandBatch.complete(
                "orders.trade", StableBusinessId.of("CMD-2"), 1L, 1, items("A"), FINGERPRINT)
                .withCursor(new SourceCursor(FEED, 1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Feed");
    }

    @Test
    @DisplayName("批次使用稳定标识与指纹，不使用数据库自增主键")
    void identityIsStableAndFingerprinted() {
        SourceCommandBatch<String> batch = SourceCommandBatch.complete(
                FEED, StableBusinessId.of("CMD-1"), 7L, 1, items("A"), FINGERPRINT);

        assertThat(batch.sourceCommandId().value()).isEqualTo("CMD-1");
        assertThat(batch.commandFingerprint().value()).isEqualTo("c".repeat(64));

        Map<String, Class<?>> components = Arrays.stream(SourceCommandBatch.class.getRecordComponents())
                .collect(Collectors.toMap(
                        RecordComponent::getName, RecordComponent::getType, (left, right) -> left));
        assertThat(components.get("sourceCommandId")).isEqualTo(StableBusinessId.class);
        assertThat(components).doesNotContainKeys("id", "primaryKey", "databaseId");
    }

    private static List<BatchItem<String>> items(String... payloads) {
        List<BatchItem<String>> result = new ArrayList<>();
        for (int index = 0; index < payloads.length; index++) {
            result.add(new BatchItem<>(index, payloads[index], ContentFingerprint.of("d".repeat(64))));
        }
        return result;
    }
}
