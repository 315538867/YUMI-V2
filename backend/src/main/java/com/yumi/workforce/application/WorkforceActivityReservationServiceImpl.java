package com.yumi.workforce.application;

import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.workforce.api.ActivityReservationRefs;
import com.yumi.workforce.api.AssignmentSubjectRef;
import com.yumi.workforce.api.AssignmentSubjectSnapshotBundle;
import com.yumi.workforce.api.AssignmentSubjectType;
import com.yumi.workforce.api.ChangeWorkforceActivityCommand;
import com.yumi.workforce.api.PreparedActivityRelease;
import com.yumi.workforce.api.PreparedActivityReservation;
import com.yumi.workforce.api.PreparedActivityReservationChange;
import com.yumi.workforce.api.ReleaseWorkforceActivityCommand;
import com.yumi.workforce.api.ReservationScopeType;
import com.yumi.workforce.api.ReservationStatus;
import com.yumi.workforce.api.ReserveWorkforceActivityCommand;
import com.yumi.workforce.api.WorkforceActivityReservationParticipant;
import com.yumi.workforce.application.port.ActivityReservationRepository;
import com.yumi.workforce.infrastructure.JdbcActivityReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 活动引用守卫实现（生产与订单在同一事务内调用）。
 *
 * <p>规则来源：`docs/一期/10-人员与小组模块.md` §5.4、§9、§10：
 * <ul>
 *   <li>守卫统一按 {@code guard_type + guarded_business_id} 排序加锁；首次使用幂等建行后加锁；</li>
 *   <li>小组任务同日预留小组、全部成员、效率标准与每日时间标准；</li>
 *   <li>同一任务、同一范围只能存在一条有效预留；重复调用幂等返回既有预留；</li>
 *   <li>调整在一个事务中释放不再使用的预留并建立新预留；</li>
 *   <li>释放只改状态与计数，不删除历史；重复释放幂等。</li>
 * </ul>
 *
 * <p>锁序：员工 → 小组 → 效率标准 → 每日时间标准，与人员变更路径一致，避免交叉死锁。
 */
public class WorkforceActivityReservationServiceImpl implements WorkforceActivityReservationParticipant {

    private final ActivityReservationRepository reservations;

    private final WorkforceSnapshotServiceImpl snapshots;

    private final Clock clock;

    public WorkforceActivityReservationServiceImpl(
            ActivityReservationRepository reservations,
            WorkforceSnapshotServiceImpl snapshots,
            Clock clock) {
        this.reservations = reservations;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PreparedActivityReservation prepareReserve(ReserveWorkforceActivityCommand command) {
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_RESERVE_COMMAND", "预留命令不能为空");
        }

        // 先锁执行主体守卫，再读取并校验资格：否则“校验通过 → 加锁”之间存在窗口，
        // 期间资格可能被撤销，导致有活动引用的资格被撤销（WF-03 核心不变量）。
        ScopeKey subjectScope = new ScopeKey(
                command.subject().subjectType() == AssignmentSubjectType.TEAM
                        ? ReservationScopeType.TEAM : ReservationScopeType.EMPLOYEE,
                command.subject().businessId());
        reservations.lockGuard(subjectScope.scopeType(), subjectScope.businessId());
        List<PreparedActivityReservation.ActivityGuardRef> guards = new ArrayList<>();
        guards.add(new PreparedActivityReservation.ActivityGuardRef(
                subjectScope.scopeType(), subjectScope.businessId()));

        AssignmentSubjectSnapshotBundle preview = snapshots.previewAssignmentSnapshot(
                command.subject(), command.workTypeCode(), command.workDate());

        // 保留完整范围集合（提交时按它建立预留）；主体守卫已锁，重复加锁是幂等的
        Set<ScopeKey> scopes = planScopes(command.subject(), preview);
        List<PreparedActivityReservation.ActivityGuardRef> remaining = lockScopes(scopes);
        for (var guard : remaining) {
            if (!guard.guardType().equals(subjectScope.scopeType())
                    || !guard.guardedBusinessId().equals(subjectScope.businessId())) {
                guards.add(guard);
            }
        }

        return new PreparedActivityReservation(
                command,
                preview,
                scopes.stream()
                        .map(scope -> new PreparedActivityReservation.ReservationScopeRef(
                                scope.scopeType(), scope.businessId()))
                        .toList(),
                guards);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ActivityReservationRefs commitReserve(PreparedActivityReservation prepared) {
        if (prepared == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_PREPARED", "预留准备结果不能为空");
        }
        // 固化快照唯一键：此刻已持全部来源锁，校验结果仍然有效
        AssignmentSubjectSnapshotBundle bundle = snapshots.acquireCurrentAssignmentSnapshot(
                prepared.command().subject(),
                prepared.command().workTypeCode(),
                prepared.command().workDate());

        List<ActivityReservationRefs.Reservation> active = new ArrayList<>();
        for (PreparedActivityReservation.ReservationScopeRef scope : prepared.scopeRefs()) {
            var existing = reservations.findActiveScope(
                    prepared.command().source(), scope.scopeType(), scope.businessId());
            if (existing.isPresent()) {
                active.add(new ActivityReservationRefs.Reservation(
                        existing.orElseThrow().reservationBusinessId(),
                        existing.orElseThrow().scopeType(),
                        existing.orElseThrow().reservedBusinessId(),
                        existing.orElseThrow().status()));
                continue;
            }
            String reservationId = JdbcActivityReservationRepository.newReservationId();
            reservations.insertReservation(new ActivityReservationRepository.ReservationRecord(
                    reservationId,
                    prepared.command().source().sourceModule(),
                    prepared.command().source().sourceType(),
                    prepared.command().source().sourceBusinessId(),
                    scope.scopeType(),
                    scope.businessId(),
                    bundle.subjectSnapshot().snapshotBusinessId(),
                    standardSnapshotRefOf(scope.scopeType(), bundle),
                    prepared.command().workTypeCode(),
                    prepared.command().workDate(),
                    ReservationStatus.ACTIVE));
            reservations.incrementGuard(scope.scopeType(), scope.businessId());
            active.add(new ActivityReservationRefs.Reservation(
                    reservationId, scope.scopeType(), scope.businessId(), ReservationStatus.ACTIVE));
        }

        return new ActivityReservationRefs(prepared.command().source(), active, bundle);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PreparedActivityReservationChange prepareChange(ChangeWorkforceActivityCommand command) {
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_CHANGE_COMMAND", "调整命令不能为空");
        }
        List<ActivityReservationRepository.ReservationRecord> existingActive =
                reservations.findActive(command.source());
        if (existingActive.isEmpty()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "WORKFORCE_RESERVATION_NOT_FOUND",
                    "任务没有有效预留，不能调整");
        }

        AssignmentSubjectSnapshotBundle preview = snapshots.previewAssignmentSnapshot(
                command.subject(), command.workTypeCode(), command.workDate());
        Set<ScopeKey> targetScopes = planScopes(command.subject(), preview);

        // 需要同时锁旧范围与新范围，释放与建立同事务完成
        Set<ScopeKey> allScopes = new LinkedHashSet<>(targetScopes);
        existingActive.forEach(record -> allScopes.add(
                new ScopeKey(record.scopeType(), record.reservedBusinessId())));
        List<PreparedActivityReservation.ActivityGuardRef> guards = lockScopes(allScopes);

        PreparedActivityReservation prepared = new PreparedActivityReservation(
                new ReserveWorkforceActivityCommand(
                        command.source(), command.subject(), command.workTypeCode(), command.workDate(),
                        command.memberEmployeeBusinessIds()),
                preview,
                targetScopes.stream()
                        .map(scope -> new PreparedActivityReservation.ReservationScopeRef(
                                scope.scopeType(), scope.businessId()))
                        .toList(),
                guards);

        return new PreparedActivityReservationChange(
                prepared,
                new ActivityReservationRefs(
                        command.source(),
                        existingActive.stream()
                                .map(record -> new ActivityReservationRefs.Reservation(
                                        record.reservationBusinessId(), record.scopeType(),
                                        record.reservedBusinessId(), record.status()))
                                .toList(),
                        preview));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ActivityReservationRefs commitChange(PreparedActivityReservationChange prepared) {
        if (prepared == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_PREPARED", "调整准备结果不能为空");
        }
        PreparedActivityReservation target = prepared.prepared();
        Set<String> targetKeys = target.scopeRefs().stream()
                .map(scope -> scope.scopeType() + "|" + scope.businessId())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        // 先释放不再使用的范围
        for (ActivityReservationRefs.Reservation reservation : prepared.existing().reservations()) {
            String key = reservation.scopeType() + "|" + reservation.reservedBusinessId();
            if (!targetKeys.contains(key)) {
                releaseOne(reservation.reservationBusinessId(), reservation.scopeType(),
                        reservation.reservedBusinessId());
            }
        }

        // 再建立新范围（幂等复用已存在的有效预留）
        AssignmentSubjectSnapshotBundle bundle = snapshots.acquireCurrentAssignmentSnapshot(
                target.command().subject(), target.command().workTypeCode(), target.command().workDate());
        List<ActivityReservationRefs.Reservation> active = new ArrayList<>();
        for (PreparedActivityReservation.ReservationScopeRef scope : target.scopeRefs()) {
            var existing = reservations.findActiveScope(
                    target.command().source(), scope.scopeType(), scope.businessId());
            if (existing.isPresent()) {
                active.add(new ActivityReservationRefs.Reservation(
                        existing.orElseThrow().reservationBusinessId(), scope.scopeType(),
                        scope.businessId(), existing.orElseThrow().status()));
                continue;
            }
            String reservationId = JdbcActivityReservationRepository.newReservationId();
            reservations.insertReservation(new ActivityReservationRepository.ReservationRecord(
                    reservationId,
                    target.command().source().sourceModule(),
                    target.command().source().sourceType(),
                    target.command().source().sourceBusinessId(),
                    scope.scopeType(),
                    scope.businessId(),
                    bundle.subjectSnapshot().snapshotBusinessId(),
                    standardSnapshotRefOf(scope.scopeType(), bundle),
                    target.command().workTypeCode(),
                    target.command().workDate(),
                    ReservationStatus.ACTIVE));
            reservations.incrementGuard(scope.scopeType(), scope.businessId());
            active.add(new ActivityReservationRefs.Reservation(
                    reservationId, scope.scopeType(), scope.businessId(), ReservationStatus.ACTIVE));
        }

        return new ActivityReservationRefs(target.command().source(), active, bundle);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PreparedActivityRelease prepareRelease(ReleaseWorkforceActivityCommand command) {
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_RELEASE_COMMAND", "释放命令不能为空");
        }
        List<ActivityReservationRepository.ReservationRecord> active = reservations.findActive(command.source());
        if (active.isEmpty()) {
            // 已全部释放：幂等返回，不再锁定
            return new PreparedActivityRelease(command.source(), List.of());
        }
        Set<ScopeKey> scopes = new LinkedHashSet<>();
        active.forEach(record -> scopes.add(new ScopeKey(record.scopeType(), record.reservedBusinessId())));
        lockScopes(scopes);

        return new PreparedActivityRelease(
                command.source(),
                active.stream()
                        .map(record -> new ActivityReservationRefs.Reservation(
                                record.reservationBusinessId(), record.scopeType(),
                                record.reservedBusinessId(), record.status()))
                        .toList());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void commitRelease(PreparedActivityRelease prepared) {
        if (prepared == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_PREPARED", "释放准备结果不能为空");
        }
        for (ActivityReservationRefs.Reservation reservation : prepared.activeReservations()) {
            releaseOne(reservation.reservationBusinessId(), reservation.scopeType(),
                    reservation.reservedBusinessId());
        }
    }

    private void releaseOne(String reservationBusinessId, ReservationScopeType scopeType, String businessId) {
        Instant releasedAt = clock.instant();
        boolean released = reservations.release(reservationBusinessId, releasedAt);
        if (released) {
            reservations.decrementGuard(scopeType, businessId);
        }
    }

    /** 预留范围：员工/小组 + 效率标准 + 每日时间标准（小组任务）。 */
    private static Set<ScopeKey> planScopes(
            AssignmentSubjectRef subject, AssignmentSubjectSnapshotBundle bundle) {
        Set<ScopeKey> scopes = new LinkedHashSet<>();
        if (subject.subjectType() == AssignmentSubjectType.EMPLOYEE) {
            scopes.add(new ScopeKey(ReservationScopeType.EMPLOYEE, subject.businessId()));
        } else {
            scopes.add(new ScopeKey(ReservationScopeType.TEAM, subject.businessId()));
            for (String memberId : bundle.subjectView().members().stream()
                    .map(com.yumi.workforce.api.AssignmentSubjectSnapshotView.MemberSnapshot::employeeBusinessId)
                    .toList()) {
                scopes.add(new ScopeKey(ReservationScopeType.EMPLOYEE, memberId));
            }
            if (bundle.efficiencyStandardSnapshot() != null) {
                scopes.add(new ScopeKey(
                        ReservationScopeType.EFFICIENCY_STANDARD,
                        bundle.efficiencyStandardSnapshot().standardBusinessId()));
            }
        }
        scopes.add(new ScopeKey(
                ReservationScopeType.DAILY_WORK_TIME_STANDARD,
                bundle.dailyWorkTimeSnapshot().standardBusinessId()));
        return scopes;
    }

    /** 统一锁序：员工 → 小组 → 效率标准 → 每日时间标准，各级内按稳定标识排序。 */
    private List<PreparedActivityReservation.ActivityGuardRef> lockScopes(Set<ScopeKey> scopes) {
        List<ScopeKey> ordered = scopes.stream()
                .sorted(Comparator.comparingInt((ScopeKey key) -> key.scopeType().ordinal())
                        .thenComparing(ScopeKey::businessId))
                .toList();
        List<PreparedActivityReservation.ActivityGuardRef> guards = new ArrayList<>();
        for (ScopeKey scope : ordered) {
            reservations.lockGuard(scope.scopeType(), scope.businessId());
            guards.add(new PreparedActivityReservation.ActivityGuardRef(scope.scopeType(), scope.businessId()));
        }
        return guards;
    }

    private static String standardSnapshotRefOf(
            ReservationScopeType scopeType, AssignmentSubjectSnapshotBundle bundle) {
        return switch (scopeType) {
            case EFFICIENCY_STANDARD -> bundle.efficiencyStandardSnapshot() == null
                    ? null : bundle.efficiencyStandardSnapshot().snapshotBusinessId();
            case DAILY_WORK_TIME_STANDARD -> bundle.dailyWorkTimeSnapshot().snapshotBusinessId();
            default -> bundle.subjectSnapshot().snapshotBusinessId();
        };
    }

    /** 守卫键：类型 + 稳定标识。 */
    private record ScopeKey(ReservationScopeType scopeType, String businessId) {
    }
}
