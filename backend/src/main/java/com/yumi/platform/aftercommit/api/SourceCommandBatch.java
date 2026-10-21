package com.yumi.platform.aftercommit.api;

import com.yumi.platform.api.value.ContentFingerprint;
import com.yumi.platform.api.value.StableBusinessId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一条来源命令产生的不可拆分来源项批次。
 *
 * <p>同一来源命令的反向（REVERSE）与替代（APPLY）项必须整体消费：消费者按完整批次更新有效
 * 投影和来源版本，成功后才推进 {@link SourceCursor}。分页读到半组更正时构造
 * {@link #partial} 批次，其 {@link #isPublishable()} 恒为 {@code false}，不得冒充完整结果，
 * 也不得让水位越过失败或缺失项。
 *
 * @param feedCode 来源 Feed 稳定代码，游标归属于同一 Feed
 * @param sourceCommandId 产生本批次的来源命令稳定标识
 * @param cursor 本批次末尾对应的来源游标
 * @param declaredItemCount 来源命令声明的项数
 * @param items 已读取到的来源项，按 {@link BatchItem#index()} 升序且从 0 连续编号
 * @param commandFingerprint 来源命令的规范化内容指纹
 * @param completeness 批次是否完整
 * @param <T> 来源项载荷类型，由 Feed 提供方定义
 */
public record SourceCommandBatch<T>(
        String feedCode,
        StableBusinessId sourceCommandId,
        SourceCursor cursor,
        int declaredItemCount,
        List<BatchItem<T>> items,
        ContentFingerprint commandFingerprint,
        BatchCompleteness completeness) {

    public SourceCommandBatch {
        requireNonBlank(feedCode, "来源 Feed 代码不能为空");
        Objects.requireNonNull(sourceCommandId, "来源命令标识不能为空");
        Objects.requireNonNull(cursor, "来源游标不能为空");
        Objects.requireNonNull(commandFingerprint, "来源命令指纹不能为空");
        Objects.requireNonNull(completeness, "批次完整性不能为空");
        Objects.requireNonNull(items, "来源项列表不能为空");
        if (!feedCode.equals(cursor.feedCode())) {
            throw new IllegalArgumentException(
                    "来源游标必须属于同一 Feed：" + cursor.feedCode() + " 与 " + feedCode);
        }
        if (declaredItemCount <= 0) {
            throw new IllegalArgumentException("来源命令批次不能为空：声明项数必须为正数");
        }
        if (items.size() > declaredItemCount) {
            throw new IllegalArgumentException(
                    "读取到的来源项多于声明项数：" + items.size() + " > " + declaredItemCount);
        }
        validateIndexes(items);
        items = List.copyOf(items);
    }

    /**
     * 构造完整批次；项数、序号或声明不一致时拒绝，不允许把不完整批次声明为完整。
     */
    public static <T> SourceCommandBatch<T> complete(
            String feedCode,
            StableBusinessId commandId,
            long position,
            int declaredItemCount,
            List<BatchItem<T>> items,
            ContentFingerprint fingerprint) {
        Objects.requireNonNull(items, "来源项列表不能为空");
        if (declaredItemCount <= 0 || items.isEmpty()) {
            throw new IllegalArgumentException(
                    "来源命令批次不能为空：更正命令必须带有明确的反向或替代项");
        }
        if (items.size() != declaredItemCount) {
            throw new IllegalArgumentException(
                    "来源项不完整，不能声明为完整批次：已读取 " + items.size()
                            + " 项，声明 " + declaredItemCount + " 项");
        }
        return new SourceCommandBatch<>(
                feedCode, commandId, new SourceCursor(feedCode, position), declaredItemCount,
                items, fingerprint, BatchCompleteness.COMPLETE);
    }

    /**
     * 构造不完整批次：分页或读取中断时的显式状态，消费者不能据此推进水位。
     */
    public static <T> SourceCommandBatch<T> partial(
            String feedCode,
            StableBusinessId commandId,
            long position,
            int declaredItemCount,
            List<BatchItem<T>> items,
            ContentFingerprint fingerprint) {
        Objects.requireNonNull(items, "来源项列表不能为空");
        if (declaredItemCount <= 0 || items.isEmpty()) {
            throw new IllegalArgumentException(
                    "来源命令批次不能为空：更正命令必须带有明确的反向或替代项");
        }
        return new SourceCommandBatch<>(
                feedCode, commandId, new SourceCursor(feedCode, position), declaredItemCount,
                items, fingerprint, BatchCompleteness.INCOMPLETE);
    }

    /** 只有完整批次可以更新有效投影并推进上游水位。 */
    public boolean isPublishable() {
        return completeness == BatchCompleteness.COMPLETE && items.size() == declaredItemCount;
    }

    /** 返回使用给定游标的同内容批次；游标必须属于同一 Feed。 */
    public SourceCommandBatch<T> withCursor(SourceCursor other) {
        Objects.requireNonNull(other, "来源游标不能为空");
        if (!feedCode.equals(other.feedCode())) {
            throw new IllegalArgumentException(
                    "来源游标必须属于同一 Feed：" + other.feedCode() + " 与 " + feedCode);
        }
        return new SourceCommandBatch<>(
                feedCode, sourceCommandId, other, declaredItemCount, items,
                commandFingerprint, completeness);
    }

    /** 缺失项数量；完整批次的缺失项为 0。 */
    public int missingItemCount() {
        return declaredItemCount - items.size();
    }

    private static void validateIndexes(List<? extends BatchItem<?>> items) {
        Set<Integer> seen = new HashSet<>();
        for (BatchItem<?> item : items) {
            Objects.requireNonNull(item, "来源项不能为空");
            if (!seen.add(item.index())) {
                throw new IllegalArgumentException("来源项序号重复：" + item.index());
            }
        }
        for (int expected = 0; expected < items.size(); expected++) {
            if (!seen.contains(expected)) {
                throw new IllegalArgumentException(
                        "来源项序号必须从 0 连续递增，缺少序号：" + expected);
            }
        }
    }

    private static void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
