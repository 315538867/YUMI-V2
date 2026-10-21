package com.yumi.product.application.port;

import java.math.BigDecimal;
import java.util.List;

/** 商品录入配置持久化端口。 */
public interface ProductEntryConfigurationRepository {

    DefaultsRecord loadDefaults();

    boolean updateDefaults(
            BigDecimal glueLossRate, BigDecimal glueUnitPrice, BigDecimal colorPasteUnitPrice,
            long expectedRowVersion, java.time.Instant now, String operatorId);

    List<StarRecord> loadMakingStars();

    void upsertMakingStar(StarRecord record, java.time.Instant now, String operatorId);

    List<ProcessTimeRecord> loadProcessTimeOptions(String processCode);

    void upsertProcessTimeOption(ProcessTimeRecord record, java.time.Instant now, String operatorId);

    /** 录入默认值。 */
    record DefaultsRecord(
            BigDecimal glueLossRate, BigDecimal glueUnitPrice, BigDecimal colorPasteUnitPrice, long rowVersion) {
    }

    /** 制作星级。 */
    record StarRecord(
            String starCode, String name, int standardMinutes, int sortOrder, String status, long rowVersion) {
    }

    /** 工序标准时间选项。 */
    record ProcessTimeRecord(
            String optionBusinessId, String processCode, String optionCode, String name,
            int standardMinutes, int sortOrder, String status, long rowVersion) {
    }
}
