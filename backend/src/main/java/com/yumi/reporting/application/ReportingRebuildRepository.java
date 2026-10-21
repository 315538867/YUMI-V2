package com.yumi.reporting.application;

import java.time.Instant;
import java.util.List;

/** 报表影子版本持久化端口。 */
public interface ReportingRebuildRepository {

    void insertVersion(String versionBusinessId, String status, Instant builtAt);

    void copyLiveRecords(String versionBusinessId);

    long countLiveRecords(String feedCode);

    long countVersionRecords(String versionBusinessId, String feedCode);

    Long maxLiveSourceVersion(String feedCode);

    Long maxVersionSourceVersion(String versionBusinessId, String feedCode);

    long countVersionRecords(String versionBusinessId);

    String activeVersionId();

    void retireActiveVersions(Instant retiredAt);

    void activateVersion(String versionBusinessId, long recordCount, int differenceCount, Instant activatedAt);

    void markFailed(String versionBusinessId, int differenceCount);

    void setActivePointer(String versionBusinessId);

    void insertDifference(
            String differenceBusinessId, String versionBusinessId, String feedCode, String detail);

    List<VersionRecord> listVersions();

    List<DifferenceRecord> listDifferences(String versionBusinessId);

    record VersionRecord(
            String versionBusinessId, String status, long recordCount, int differenceCount,
            Instant builtAt, Instant activatedAt) {
    }

    record DifferenceRecord(String differenceBusinessId, String feedCode, String detail) {
    }
}
