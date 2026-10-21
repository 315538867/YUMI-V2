package com.yumi.platform.numbering.application;

import com.yumi.platform.api.numbering.BusinessNumberServiceContract;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 业务编号服务。
 *
 * <p>规则来源：`docs/一期/04-公共业务与技术规则.md` §1。编号只用于展示与搜索，不作为跨模块
 * 关联主键；并发创建同类业务时由数据库行锁保证唯一，失败重试不另造已成功结果（幂等由命令层保证）。
 *
 * <p>实现使用 {@code SELECT ... FOR UPDATE} 更新序列表：同一序列的并发调用会被串行化，且
 * 编号在业务事务内分配——事务回滚时编号一并回退，避免出现空洞编号被误认为已用。
 */
@Transactional(propagation = Propagation.MANDATORY)
public class BusinessNumberService implements BusinessNumberServiceContract {

    private final JdbcClient jdbc;

    public BusinessNumberService(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JdbcClient 不能为空");
    }

    @Override
    public String nextNumber(String sequenceCode, String prefix, int width) {
        Objects.requireNonNull(sequenceCode, "序列代码不能为空");
        Objects.requireNonNull(prefix, "编号前缀不能为空");
        if (sequenceCode.isBlank()) {
            throw new IllegalArgumentException("序列代码不能为空白");
        }
        if (width < 1 || width > 18) {
            throw new IllegalArgumentException("流水号位数必须在 1—18 之间：" + width);
        }

        jdbc.sql("""
                        INSERT INTO platform_business_sequences (sequence_code, next_value, updated_at)
                        VALUES (:sequenceCode, 1, UTC_TIMESTAMP(6))
                        ON DUPLICATE KEY UPDATE sequence_code = sequence_code
                        """)
                .param("sequenceCode", sequenceCode)
                .update();

        long current = jdbc.sql("""
                        SELECT next_value FROM platform_business_sequences
                        WHERE sequence_code = :sequenceCode
                        FOR UPDATE
                        """)
                .param("sequenceCode", sequenceCode)
                .query(Long.class)
                .single();

        jdbc.sql("""
                        UPDATE platform_business_sequences
                        SET next_value = next_value + 1, updated_at = UTC_TIMESTAMP(6)
                        WHERE sequence_code = :sequenceCode
                        """)
                .param("sequenceCode", sequenceCode)
                .update();

        return prefix + String.format("%0" + width + "d", current);
    }

    /** 按业务日期生成序列代码，例如 {@code ORD-2026}。 */
    public static String yearlySequence(String prefix, LocalDate businessDate) {
        return prefix + "-" + businessDate.getYear();
    }

    /** 一期统一业务日期为 UTC 存储、工作室时区解释；此处仅用于序列代码。 */
    public static LocalDate businessDateOf(java.time.Instant instant, java.time.ZoneId zone) {
        return instant.atZone(zone).toLocalDate();
    }

    static ZoneOffset storageZone() {
        return ZoneOffset.UTC;
    }
}
