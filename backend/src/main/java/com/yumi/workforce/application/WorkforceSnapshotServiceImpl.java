package com.yumi.workforce.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.value.ContentFingerprint;
import com.yumi.workforce.api.AssignmentSubjectCurrentView;
import com.yumi.workforce.api.AssignmentSubjectQuery;
import com.yumi.workforce.api.AssignmentSubjectRef;
import com.yumi.workforce.api.AssignmentSubjectSnapshotBundle;
import com.yumi.workforce.api.AssignmentSubjectSnapshotRef;
import com.yumi.workforce.api.AssignmentSubjectSnapshotService;
import com.yumi.workforce.api.AssignmentSubjectSnapshotView;
import com.yumi.workforce.api.AssignmentSubjectType;
import com.yumi.workforce.api.EmployeeView;
import com.yumi.workforce.application.port.WorkforceRepository;
import com.yumi.workforce.application.port.WorkforceSnapshotRepository;
import com.yumi.workforce.infrastructure.JdbcWorkforceSnapshotRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 执行主体与时间标准快照实现。
 *
 * <p>规则来源：`docs/一期/10-人员与小组模块.md` §7.2、§10：
 * <ul>
 *   <li>快照按“稳定标准标识 + 内容版本”区分：二人与三人效率标准即使同为版本 1 也不碰撞；</li>
 *   <li>历史小组成员在离职后仍可解析，快照内容不随当前成员变化；</li>
 *   <li>同身份同版本并发创建只保留一条，载荷指纹不同则报版本一致性错误。</li>
 * </ul>
 */
public class WorkforceSnapshotServiceImpl implements AssignmentSubjectSnapshotService {

    private static final String EFFICIENCY_STANDARD = "TEAM_EFFICIENCY";
    private static final String DAILY_STANDARD = "DAILY_WORK_TIME";

    private final WorkforceSnapshotRepository snapshots;

    private final WorkforceRepository repository;

    private final WorkforceCommandService workforce;

    private final AssignmentSubjectQuery subjectQuery;

    private final AuthorizationService authorization;

    private final ObjectMapper objectMapper;

    private final Clock clock;

    public WorkforceSnapshotServiceImpl(
            WorkforceSnapshotRepository snapshots,
            WorkforceRepository repository,
            WorkforceCommandService workforce,
            AssignmentSubjectQuery subjectQuery,
            AuthorizationService authorization,
            ObjectMapper objectMapper,
            Clock clock) {
        this.snapshots = snapshots;
        this.repository = repository;
        this.workforce = workforce;
        this.subjectQuery = subjectQuery;
        this.authorization = authorization;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 预览快照内容：与 {@code acquireCurrentAssignmentSnapshot} 同样的校验与载荷，但不写唯一键。
     *
     * <p>活动预留的 {@code prepare} 必须使用本方法：此时尚未持全部来源锁，提前固化快照会把
     * 已失效的资格或旧成员写进新任务。
     */
    public AssignmentSubjectSnapshotBundle previewAssignmentSnapshot(
            AssignmentSubjectRef subject, String workTypeCode, LocalDate workDate) {
        return buildBundle(subject, workTypeCode, workDate, false);
    }

    @Override
    public AssignmentSubjectSnapshotBundle acquireCurrentAssignmentSnapshot(
            AssignmentSubjectRef subject, String workTypeCode, LocalDate workDate) {
        return buildBundle(subject, workTypeCode, workDate, true);
    }

    private AssignmentSubjectSnapshotBundle buildBundle(
            AssignmentSubjectRef subject, String workTypeCode, LocalDate workDate, boolean persist) {
        authorization.require(PermissionCodes.WORKFORCE_READ, ResourceScope.GLOBAL);
        if (subject == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_SUBJECT", "执行主体不能为空");
        }

        AssignmentSubjectCurrentView current = workforce.loadCurrent(subject);
        if (subject.subjectType() == AssignmentSubjectType.EMPLOYEE) {
            if (current.employmentStatus() != com.yumi.workforce.api.EmploymentStatus.EMPLOYED) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "EMPLOYEE_LEFT", "员工已离职，不能安排新任务");
            }
            if (workTypeCode != null
                    && !repository.hasActiveQualification(subject.businessId(), workTypeCode, workDate)) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "EMPLOYEE_NOT_QUALIFIED",
                        "员工缺少该工种的有效资格");
            }
        } else {
            if (current.teamStatus() != com.yumi.workforce.api.TeamStatus.ACTIVE) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "WORKFORCE_TEAM_INACTIVE", "小组已停用，不能安排新任务");
            }
            if (current.memberEmployeeBusinessIds().size() < 2) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "WORKFORCE_TEAM_INVALID", "小组至少需要两名成员");
            }
            for (String memberId : current.memberEmployeeBusinessIds()) {
                var employee = repository.findEmployee(memberId);
                if (employee.isEmpty()
                        || employee.orElseThrow().employmentStatus()
                                != com.yumi.workforce.api.EmploymentStatus.EMPLOYED) {
                    throw new ApiException(
                            ApiErrorType.BUSINESS_REJECTION, "EMPLOYEE_LEFT",
                            "小组成员已离职：" + memberId);
                }
                if (workTypeCode != null
                        && !repository.hasActiveQualification(memberId, workTypeCode, workDate)) {
                    throw new ApiException(
                            ApiErrorType.BUSINESS_REJECTION, "EMPLOYEE_NOT_QUALIFIED",
                            "小组成员缺少该工种资格：" + memberId);
                }
            }
            if (current.efficiencyFactor() == null) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "WORKFORCE_STANDARD_INVALID",
                        "缺少该人数的小组效率标准");
            }
        }

        AssignmentSubjectSnapshotRef subjectSnapshot = persist
                ? acquireSubjectSnapshot(subject, current)
                : previewSubjectRef(subject, current);
        AssignmentSubjectSnapshotView subjectView = toSubjectView(subjectSnapshot, current);

        AssignmentSubjectSnapshotBundle.StandardSnapshotRef efficiencyRef = null;
        if (subject.subjectType() == AssignmentSubjectType.TEAM) {
            efficiencyRef = persist
                    ? acquireCurrentTeamEfficiency(current.memberEmployeeBusinessIds().size())
                    : previewStandardRef(EFFICIENCY_STANDARD, current.memberEmployeeBusinessIds().size());
        }
        AssignmentSubjectSnapshotBundle.StandardSnapshotRef dailyRef = persist
                ? acquireCurrentDailyStandard()
                : previewDailyRef();

        return new AssignmentSubjectSnapshotBundle(
                subjectSnapshot,
                subjectView,
                efficiencyRef,
                dailyRef,
                repository.findDailyStandard()
                        .map(WorkforceRepository.DailyStandardRecord::effectiveMinutes)
                        .orElseThrow(() -> new ApiException(
                                ApiErrorType.NOT_FOUND, "WORKFORCE_STANDARD_INVALID",
                                "每日有效时间标准不存在")),
                current.qualifications());
    }

    @Override
    public AssignmentSubjectSnapshotView resolve(AssignmentSubjectSnapshotRef ref) {
        authorization.require(PermissionCodes.WORKFORCE_READ, ResourceScope.GLOBAL);
        if (ref == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_SNAPSHOT", "快照引用不能为空");
        }
        var record = snapshots.findSnapshotByBusinessId(ref.snapshotBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "WORKFORCE_SNAPSHOT_NOT_FOUND", "执行主体快照不存在"));
        Map<String, Object> payload = readJson(record.payloadJson());
        List<AssignmentSubjectSnapshotView.MemberSnapshot> members = new ArrayList<>();
        if (record.memberRefsJson() != null) {
            List<Map<String, Object>> refs = readJsonList(record.memberRefsJson());
            for (Map<String, Object> member : refs) {
                members.add(new AssignmentSubjectSnapshotView.MemberSnapshot(
                        text(member, "employeeBusinessId"),
                        text(member, "employeeNo"),
                        text(member, "name")));
            }
        }
        return new AssignmentSubjectSnapshotView(
                toRef(record),
                text(payload, "name"),
                optionalText(payload, "employeeNo"),
                optionalText(payload, "teamNo"),
                members,
                payload.get("efficiencyFactor") == null
                        ? null
                        : new BigDecimal(payload.get("efficiencyFactor").toString()),
                optionalText(payload, "workTypeCode"));
    }

    @Override
    public AssignmentSubjectSnapshotBundle.StandardSnapshotRef acquireCurrentDailyStandard() {
        WorkforceRepository.DailyStandardRecord daily = repository.findDailyStandard()
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "WORKFORCE_STANDARD_INVALID", "每日有效时间标准不存在"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("effectiveMinutes", daily.effectiveMinutes());
        payload.put("contentVersion", daily.contentVersion());
        return acquireStandardSnapshot(DAILY_STANDARD, daily.standardBusinessId(),
                daily.contentVersion(), payload);
    }

    @Override
    public AssignmentSubjectSnapshotBundle.StandardSnapshotRef acquireCurrentTeamEfficiency(int memberCount) {
        WorkforceRepository.EfficiencyStandardRecord standard = repository
                .findEfficiencyStandardByMemberCount(memberCount)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "WORKFORCE_STANDARD_INVALID",
                        "缺少该人数的小组效率标准：" + memberCount));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("memberCount", standard.memberCount());
        payload.put("efficiencyFactor", standard.efficiencyFactor().toPlainString());
        payload.put("contentVersion", standard.contentVersion());
        return acquireStandardSnapshot(
                EFFICIENCY_STANDARD, standard.standardBusinessId(), standard.contentVersion(), payload);
    }

    /** 预览主体引用：不写唯一键，仅用于 prepare 阶段的一致性校验。 */
    private AssignmentSubjectSnapshotRef previewSubjectRef(
            AssignmentSubjectRef subject, AssignmentSubjectCurrentView current) {
        return new AssignmentSubjectSnapshotRef(
                subject.subjectType(),
                "PREVIEW",
                subject.businessId(),
                0L,
                current.contentVersion(),
                ContentFingerprint.of(sha256(
                        "PREVIEW|" + subject.businessId() + "|" + current.contentVersion())));
    }

    private AssignmentSubjectSnapshotBundle.StandardSnapshotRef previewStandardRef(
            String standardType, int memberCount) {
        var standard = repository.findEfficiencyStandardByMemberCount(memberCount)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "WORKFORCE_STANDARD_INVALID",
                        "缺少该人数的小组效率标准：" + memberCount));
        return new AssignmentSubjectSnapshotBundle.StandardSnapshotRef(
                standardType,
                standard.standardBusinessId(),
                "PREVIEW",
                standard.contentVersion(),
                ContentFingerprint.of(sha256(
                        "PREVIEW|" + standard.standardBusinessId() + "|" + standard.contentVersion())));
    }

    private AssignmentSubjectSnapshotBundle.StandardSnapshotRef previewDailyRef() {
        WorkforceRepository.DailyStandardRecord daily = repository.findDailyStandard()
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "WORKFORCE_STANDARD_INVALID", "每日有效时间标准不存在"));
        return new AssignmentSubjectSnapshotBundle.StandardSnapshotRef(
                DAILY_STANDARD,
                daily.standardBusinessId(),
                "PREVIEW",
                daily.contentVersion(),
                ContentFingerprint.of(sha256(
                        "PREVIEW|" + daily.standardBusinessId() + "|" + daily.contentVersion())));
    }

    private AssignmentSubjectSnapshotRef acquireSubjectSnapshot(
            AssignmentSubjectRef subject, AssignmentSubjectCurrentView current) {
        long contentVersion = current.contentVersion();
        var existing = subject.subjectType() == AssignmentSubjectType.EMPLOYEE
                ? snapshots.findEmployeeSnapshot(subject.businessId(), contentVersion)
                : snapshots.findTeamSnapshot(subject.businessId(), contentVersion);
        if (existing.isPresent()) {
            return toRef(existing.orElseThrow());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subjectType", subject.subjectType().name());
        payload.put("businessId", subject.businessId());
        payload.put("name", current.name());
        payload.put("contentVersion", contentVersion);
        payload.put("workTypeCode", null);
        if (subject.subjectType() == AssignmentSubjectType.EMPLOYEE) {
            var employee = repository.findEmployee(subject.businessId()).orElseThrow();
            payload.put("employeeNo", employee.employeeNo());
            payload.put("employmentStatus", employee.employmentStatus().name());
        } else {
            var team = repository.findTeam(subject.businessId()).orElseThrow();
            payload.put("teamNo", team.teamNo());
            payload.put("efficiencyFactor",
                    current.efficiencyFactor() == null ? null : current.efficiencyFactor().toPlainString());
            payload.put("memberIds", current.memberEmployeeBusinessIds());
        }

        String memberRefsJson = null;
        if (subject.subjectType() == AssignmentSubjectType.TEAM) {
            List<Map<String, Object>> members = new ArrayList<>();
            for (String memberId : current.memberEmployeeBusinessIds()) {
                var employee = repository.findEmployee(memberId).orElseThrow();
                Map<String, Object> member = new LinkedHashMap<>();
                member.put("employeeBusinessId", memberId);
                member.put("employeeNo", employee.employeeNo());
                member.put("name", employee.name());
                members.add(member);
            }
            memberRefsJson = toJson(members);
        }

        String payloadJson = toJson(payload);
        var record = new WorkforceSnapshotRepository.SubjectSnapshotRecord(
                "WSS-" + UUID.randomUUID(),
                subject.subjectType().name(),
                subject.businessId(),
                snapshots.maxSubjectSnapshotVersion(subject.businessId()) + 1,
                contentVersion,
                payloadJson,
                memberRefsJson,
                ContentFingerprint.of(sha256(
                        subject.businessId() + "|" + contentVersion + "|" + payloadJson
                                + "|" + String.valueOf(memberRefsJson))),
                clock.instant());

        try {
            if (subject.subjectType() == AssignmentSubjectType.EMPLOYEE) {
                snapshots.insertEmployeeSnapshot(record);
            } else {
                snapshots.insertTeamSnapshot(record);
            }
            return toRef(record);
        } catch (JdbcWorkforceSnapshotRepository.SnapshotConflict conflict) {
            var raced = subject.subjectType() == AssignmentSubjectType.EMPLOYEE
                    ? snapshots.findEmployeeSnapshot(subject.businessId(), contentVersion)
                    : snapshots.findTeamSnapshot(subject.businessId(), contentVersion);
            return raced.map(WorkforceSnapshotServiceImpl::toRef)
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.CONCURRENT_CONFLICT, "WORKFORCE_SNAPSHOT_CONFLICT",
                            "执行主体快照并发创建冲突，请重试"));
        }
    }

    private AssignmentSubjectSnapshotBundle.StandardSnapshotRef acquireStandardSnapshot(
            String standardType, String standardBusinessId, long contentVersion, Map<String, Object> payload) {
        var existing = snapshots.findStandardSnapshot(standardType, standardBusinessId, contentVersion);
        if (existing.isPresent()) {
            return toStandardRef(existing.orElseThrow());
        }
        String payloadJson = toJson(payload);
        var record = new WorkforceSnapshotRepository.StandardSnapshotRecord(
                "WSS-" + UUID.randomUUID(),
                standardType,
                standardBusinessId,
                contentVersion,
                payloadJson,
                ContentFingerprint.of(sha256(
                        standardType + "|" + standardBusinessId + "|" + contentVersion + "|" + payloadJson)),
                clock.instant());
        try {
            snapshots.insertStandardSnapshot(record);
            return toStandardRef(record);
        } catch (JdbcWorkforceSnapshotRepository.SnapshotConflict conflict) {
            return snapshots.findStandardSnapshot(standardType, standardBusinessId, contentVersion)
                    .map(WorkforceSnapshotServiceImpl::toStandardRef)
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.CONCURRENT_CONFLICT, "WORKFORCE_SNAPSHOT_CONFLICT",
                            "标准快照并发创建冲突，请重试"));
        }
    }

    private static AssignmentSubjectSnapshotView toSubjectView(
            AssignmentSubjectSnapshotRef ref, AssignmentSubjectCurrentView current) {
        List<AssignmentSubjectSnapshotView.MemberSnapshot> members =
                current.memberEmployeeBusinessIds().stream()
                        .map(memberId -> new AssignmentSubjectSnapshotView.MemberSnapshot(memberId, null, null))
                        .toList();
        return new AssignmentSubjectSnapshotView(
                ref,
                current.name(),
                null,
                null,
                members,
                current.efficiencyFactor(),
                null);
    }

    private Map<String, Object> readJson(String json) {
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "WORKFORCE_SNAPSHOT_INVALID", "快照内容无法解析");
        }
    }

    private List<Map<String, Object>> readJsonList(String json) {
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "WORKFORCE_SNAPSHOT_INVALID", "成员快照内容无法解析");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "WORKFORCE_SNAPSHOT_INVALID", "快照内容无法序列化");
        }
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "WORKFORCE_SNAPSHOT_INVALID", "快照缺少字段：" + key);
        }
        return value.toString();
    }

    private static String optionalText(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : value.toString();
    }

    private static AssignmentSubjectSnapshotRef toRef(
            WorkforceSnapshotRepository.SubjectSnapshotRecord record) {
        return new AssignmentSubjectSnapshotRef(
                AssignmentSubjectType.valueOf(record.subjectType()),
                record.snapshotBusinessId(),
                record.subjectBusinessId(),
                record.snapshotVersion(),
                record.sourceContentVersion(),
                record.fingerprint());
    }

    private static AssignmentSubjectSnapshotBundle.StandardSnapshotRef toStandardRef(
            WorkforceSnapshotRepository.StandardSnapshotRecord record) {
        return new AssignmentSubjectSnapshotBundle.StandardSnapshotRef(
                record.standardType(),
                record.standardBusinessId(),
                record.snapshotBusinessId(),
                record.sourceContentVersion(),
                record.fingerprint());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法", exception);
        }
    }
}
