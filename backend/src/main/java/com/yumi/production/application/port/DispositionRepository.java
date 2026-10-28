package com.yumi.production.application.port;

import com.yumi.production.api.DefectDispositionType;
import com.yumi.production.api.DefectReasonStatus;
import com.yumi.production.api.DefectReasonView;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.ResponsibilityType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 处置、责任与缺陷原因持久化端口。 */
public interface DispositionRepository {

    /** 按任务明细读取缺陷事实。 */
    List<DefectRow> findDefects(String taskItemBusinessId);

    List<DispositionRow> findDispositions(String defectFactBusinessId);

    /** 按处置标识查找（责任更正只需处置标识）。 */
    List<DispositionRow> findDispositionsOf(String dispositionFactBusinessId);

    Optional<TerminalExitRow> findTerminalExit(String terminalExitBusinessId);

    Optional<ResponsibilityRow> findResponsibilityByDisposition(String dispositionFactBusinessId);

    int countTerminalExits(String dispositionFactBusinessId);

    void invalidateResponsibility(String responsibilityChainBusinessId);

    void insertResponsibility(ResponsibilityRow row);

    void insertRevision(RevisionRow row);

    List<RevisionRow> findRevisions(String responsibilityChainBusinessId);

    List<DefectReasonRow> findAllDefectReasons();

    List<DefectReasonRow> findActiveDefectReasons();

    Optional<DefectReasonRow> findDefectReason(String code);

    void insertDefectReason(
            String code, String name, DefectReasonStatus status, boolean other,
            String applicableProcess, boolean requiresText);

    boolean updateDefectReason(
            String code, String name, boolean other, String applicableProcess,
            boolean requiresText, long expectedRowVersion);

    boolean updateDefectReasonStatus(
            String code, DefectReasonStatus status, long expectedRowVersion);

    /** 任务明细所属的需求与来源（用于追溯视图）。 */
    Optional<ItemContext> findItemContext(String taskItemBusinessId);

    /** 某生产来源下所有任务明细（追溯视图按来源聚合）。 */
    List<String> findItemIdsOfSource(String productionSourceBusinessId);

    /** 缺陷事实。 */
    record DefectRow(
            String defectFactBusinessId, String taskItemBusinessId, String defectReasonCode,
            String defectReasonName, int quantity) {
    }

    /** 处置事实。 */
    record DispositionRow(
            String dispositionFactBusinessId, String defectFactBusinessId, DefectDispositionType type,
            PhysicalDestination physicalDestination, int quantity, String terminalExitBusinessId,
            String repairSourceBusinessId) {
    }

    /** 终态退出。 */
    record TerminalExitRow(
            String terminalExitBusinessId, String productionSourceBusinessId, String demandBusinessId,
            String taskItemBusinessId, String exitReason, PhysicalDestination physicalDestination,
            int quantity) {
    }

    /** 责任事实。 */
    record ResponsibilityRow(
            String responsibilityChainBusinessId, String dispositionFactBusinessId,
            String terminalExitBusinessId, ResponsibilityType responsibilityType,
            String responsibleSubjectBusinessId, String waivedReason, int quantity,
            LocalDate businessDate, long rowVersion) {
    }

    /** 责任更正记录。 */
    record RevisionRow(
            String revisionBusinessId, String responsibilityChainBusinessId,
            String dispositionFactBusinessId, String effectType,
            ResponsibilityType responsibilityType, String responsibleSubjectBusinessId,
            String waivedReason, int quantity, String reasonText, long feedSequence) {
    }

    /** 缺陷原因。 */
    record DefectReasonRow(
            String defectReasonCode, String name, DefectReasonStatus status, boolean other,
            String applicableProcess, boolean requiresText, long rowVersion) {
    }

    /** 明细上下文。 */
    record ItemContext(
            String taskItemBusinessId, String demandBusinessId, String productionSourceBusinessId) {
    }

    /** 便于视图组装的缺陷原因视图转换。 */
    static DefectReasonView toView(DefectReasonRow row) {
        return new DefectReasonView(
                row.defectReasonCode(), row.name(), row.status(), row.other(),
                row.applicableProcess(), row.requiresText(), row.rowVersion());
    }

    /** 供实现层使用的时间类型。 */
    static Instant now(Instant value) {
        return value;
    }
}
