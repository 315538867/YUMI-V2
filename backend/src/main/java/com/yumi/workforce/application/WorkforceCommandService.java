package com.yumi.workforce.application;

import com.yumi.platform.api.audit.AuditService;
import com.yumi.platform.api.audit.RecordAuditCommand;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.http.RequestCorrelationHolder;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.numbering.BusinessNumberService;
import com.yumi.workforce.api.AssignmentEligibility;
import com.yumi.workforce.api.AssignmentSubjectCurrentView;
import com.yumi.workforce.api.AssignmentSubjectQuery;
import com.yumi.workforce.api.AssignmentSubjectRef;
import com.yumi.workforce.api.AssignmentSubjectType;
import com.yumi.workforce.api.ChangeQualificationCommand;
import com.yumi.workforce.api.CreateTeamCommand;
import com.yumi.workforce.api.DeactivateTeamCommand;
import com.yumi.workforce.api.EmployeeRef;
import com.yumi.workforce.api.EmployeeView;
import com.yumi.workforce.api.EmploymentStatus;
import com.yumi.workforce.api.HireEmployeeCommand;
import com.yumi.workforce.api.LeaveEmployeeCommand;
import com.yumi.workforce.api.QualificationStatus;
import com.yumi.workforce.api.RehireEmployeeCommand;
import com.yumi.workforce.api.ReservationScopeType;
import com.yumi.workforce.api.TeamRef;
import com.yumi.workforce.api.TeamStatus;
import com.yumi.workforce.api.TeamView;
import com.yumi.workforce.api.UpdateEfficiencyStandardCommand;
import com.yumi.workforce.api.UpdateEmployeeCommand;
import com.yumi.workforce.api.UpdateTeamCommand;
import com.yumi.workforce.api.UpdateWorkTimeStandardCommand;
import com.yumi.workforce.api.WorkforceApplicationService;
import com.yumi.workforce.api.WorkforceStandardQuery;
import com.yumi.workforce.api.WorkforceStandardView;
import com.yumi.workforce.application.port.ActivityReservationRepository;
import com.yumi.workforce.application.port.EmployeeRecord;
import com.yumi.workforce.application.port.TeamRecord;
import com.yumi.workforce.application.port.WorkforceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 人员与小组应用服务：就业历史、资格、小组、效率与有效时间标准。
 *
 * <p>规则来源：`docs/一期/10-人员与小组模块.md` §5、§7、§10：
 * <ul>
 *   <li>员工与平台账号完全分开：本类与命令类型都不含账号、登录名或绑定表；</li>
 *   <li>返聘沿用原员工身份，历史任务不被改写；</li>
 *   <li>减少可用能力的操作（离职、撤销资格、改成员、停用小组）先锁活动守卫，有活动引用则拒绝；</li>
 *   <li>小组至少两名当前在职员工；一期允许员工属于多个小组。</li>
 * </ul>
 */
public class WorkforceCommandService
        implements WorkforceApplicationService, WorkforceStandardQuery, AssignmentSubjectQuery {

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}]");

    private static final Pattern PHONE_ALLOWED = Pattern.compile("[0-9+()\\-\\s]*");

    private final WorkforceRepository repository;

    private final ActivityReservationRepository reservations;

    private final BusinessNumberService numbering;

    private final AuthorizationService authorization;

    private final CurrentActorProvider currentActor;

    private final AuditService audit;

    private final Clock clock;

    private final TransactionTemplate transactionTemplate;

    public WorkforceCommandService(
            WorkforceRepository repository,
            ActivityReservationRepository reservations,
            BusinessNumberService numbering,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            AuditService audit,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.reservations = reservations;
        this.numbering = numbering;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.audit = audit;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public EmployeeRef hire(HireEmployeeCommand command) {
        authorization.require(PermissionCodes.WORKFORCE_EMPLOYEE_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_EMPLOYEE", "员工资料不能为空");
        }
        String name = requireText(command.name(), "员工姓名", 80);
        String phone = normalizePhone(command.phone());
        LocalDate effectiveDate = command.effectiveDate() == null
                ? today() : command.effectiveDate();

        return transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            String operatorId = operatorId();
            var record = new EmployeeRecord(
                    0L,
                    "EMP-" + UUID.randomUUID(),
                    numbering.nextNumber("EMPLOYEE", "E-", 6),
                    name,
                    phone,
                    EmploymentStatus.EMPLOYED,
                    1,
                    1,
                    now,
                    now);
            repository.insertEmployee(record, operatorId);
            repository.insertEmploymentFact(
                    record.employeeBusinessId(), "HIRE", effectiveDate, null, now, operatorId);
            recordAudit("HIRE_EMPLOYEE", record.employeeBusinessId(), Map.of("employeeNo", record.employeeNo()));
            return toRef(record);
        });
    }

    @Override
    public EmployeeRef updateEmployee(UpdateEmployeeCommand command) {
        authorization.require(PermissionCodes.WORKFORCE_EMPLOYEE_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_EMPLOYEE", "员工资料不能为空");
        }
        String employeeBusinessId = requireText(command.employeeBusinessId(), "员工标识", 64);
        String name = requireText(command.name(), "员工姓名", 80);
        String phone = normalizePhone(command.phone());

        return transactionTemplate.execute(status -> {
            EmployeeRecord existing = requireEmployee(employeeBusinessId);
            Instant now = clock.instant();
            if (!repository.updateEmployee(
                    employeeBusinessId, name, phone, command.expectedRowVersion(), now, operatorId())) {
                throw new ApiException(
                        ApiErrorType.CONCURRENT_CONFLICT, "WORKFORCE_VERSION_CONFLICT",
                        "员工资料已被其他操作修改，请重新打开后再保存");
            }
            recordAudit("UPDATE_EMPLOYEE", employeeBusinessId, Map.of("employeeNo", existing.employeeNo()));
            return toRef(requireEmployee(employeeBusinessId));
        });
    }

    @Override
    public EmployeeRef leave(LeaveEmployeeCommand command) {
        authorization.require(PermissionCodes.WORKFORCE_EMPLOYEE_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_EMPLOYEE", "离职命令不能为空");
        }
        String employeeBusinessId = requireText(command.employeeBusinessId(), "员工标识", 64);
        LocalDate effectiveDate = command.effectiveDate() == null ? today() : command.effectiveDate();

        return transactionTemplate.execute(status -> {
            requireEmployee(employeeBusinessId);
            // 减少可用能力：先锁员工守卫，有活动引用则拒绝
            lockGuardAndRequireNoActive(ReservationScopeType.EMPLOYEE, employeeBusinessId);

            Instant now = clock.instant();
            String operatorId = operatorId();
            if (!repository.updateEmploymentStatus(
                    employeeBusinessId, EmploymentStatus.LEFT, effectiveDate,
                    command.expectedRowVersion(), now, operatorId)) {
                throw new ApiException(
                        ApiErrorType.CONCURRENT_CONFLICT, "WORKFORCE_VERSION_CONFLICT",
                        "员工资料已被其他操作修改，请重新打开后再操作");
            }
            repository.insertEmploymentFact(
                    employeeBusinessId, "LEAVE", effectiveDate, command.reason(), now, operatorId);
            recordAudit("LEAVE_EMPLOYEE", employeeBusinessId, Map.of("reason",
                    command.reason() == null ? "" : command.reason()));
            return toRef(requireEmployee(employeeBusinessId));
        });
    }

    @Override
    public EmployeeRef rehire(RehireEmployeeCommand command) {
        authorization.require(PermissionCodes.WORKFORCE_EMPLOYEE_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_EMPLOYEE", "返聘命令不能为空");
        }
        String employeeBusinessId = requireText(command.employeeBusinessId(), "员工标识", 64);
        LocalDate effectiveDate = command.effectiveDate() == null ? today() : command.effectiveDate();

        return transactionTemplate.execute(status -> {
            requireEmployee(employeeBusinessId);
            Instant now = clock.instant();
            String operatorId = operatorId();
            if (!repository.updateEmploymentStatus(
                    employeeBusinessId, EmploymentStatus.EMPLOYED, effectiveDate,
                    command.expectedRowVersion(), now, operatorId)) {
                throw new ApiException(
                        ApiErrorType.CONCURRENT_CONFLICT, "WORKFORCE_VERSION_CONFLICT",
                        "员工资料已被其他操作修改，请重新打开后再操作");
            }
            repository.insertEmploymentFact(employeeBusinessId, "REHIRE", effectiveDate, null, now, operatorId);
            recordAudit("REHIRE_EMPLOYEE", employeeBusinessId, Map.of("effectiveDate",
                    effectiveDate.toString()));
            return toRef(requireEmployee(employeeBusinessId));
        });
    }

    @Override
    public void grantWorkType(ChangeQualificationCommand command) {
        authorization.require(PermissionCodes.WORKFORCE_EMPLOYEE_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_QUALIFICATION", "资格命令不能为空");
        }
        String employeeBusinessId = requireText(command.employeeBusinessId(), "员工标识", 64);
        transactionTemplate.executeWithoutResult(status ->
                applyQualificationChange(command, QualificationStatus.ACTIVE, employeeBusinessId));
    }

    @Override
    public void revokeWorkType(ChangeQualificationCommand command) {
        authorization.require(PermissionCodes.WORKFORCE_EMPLOYEE_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_QUALIFICATION", "资格命令不能为空");
        }
        String employeeBusinessId = requireText(command.employeeBusinessId(), "员工标识", 64);
        // 守卫检查与资格撤销必须在同一事务内：分成两个事务会留下“检查通过后、撤销前”
        // 被新预留插入的窗口，导致有活动引用的资格被撤销（WF-03 的核心不变量）。
        transactionTemplate.executeWithoutResult(status -> {
            lockGuardAndRequireNoActive(ReservationScopeType.EMPLOYEE, employeeBusinessId);
            applyQualificationChange(command, QualificationStatus.REVOKED, employeeBusinessId);
        });
    }

    @Override
    public TeamRef createTeam(CreateTeamCommand command) {
        authorization.require(PermissionCodes.WORKFORCE_TEAM_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_TEAM", "小组资料不能为空");
        }
        String name = requireText(command.name(), "小组名称", 80);
        List<String> memberIds = validateMembers(command.memberEmployeeBusinessIds());

        return transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            String operatorId = operatorId();
            // 锁序：活动守卫 → 员工根
            lockGuardsInOrder(memberIds, List.of());
            for (String memberId : memberIds) {
                requireEmployed(memberId);
            }

            var record = new TeamRecord(
                    0L,
                    "TEAM-" + UUID.randomUUID(),
                    numbering.nextNumber("TEAM", "T-", 4),
                    name,
                    TeamStatus.ACTIVE,
                    1,
                    1,
                    now,
                    now);
            repository.insertTeam(record, operatorId);
            repository.replaceTeamMembers(record.teamBusinessId(), memberIds, now, operatorId);
            recordAudit("CREATE_TEAM", record.teamBusinessId(), Map.of(
                    "teamNo", record.teamNo(), "memberCount", String.valueOf(memberIds.size())));
            return toRef(record);
        });
    }

    @Override
    public TeamRef updateTeam(UpdateTeamCommand command) {
        authorization.require(PermissionCodes.WORKFORCE_TEAM_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_TEAM", "小组资料不能为空");
        }
        String teamBusinessId = requireText(command.teamBusinessId(), "小组标识", 64);
        String name = requireText(command.name(), "小组名称", 80);
        List<String> memberIds = validateMembers(command.memberEmployeeBusinessIds());

        return transactionTemplate.execute(status -> {
            TeamRecord existing = requireTeam(teamBusinessId);
            // 修改成员会减少原成员可用能力：小组与全部原成员、新成员守卫都要无活动引用
            List<String> existingMembers = repository.teamMemberIds(teamBusinessId);
            Set<String> union = new HashSet<>(existingMembers);
            union.addAll(memberIds);
            List<String> sortedUnion = union.stream().sorted().toList();
            lockGuardsInOrder(sortedUnion, List.of(teamBusinessId));
            for (String memberId : sortedUnion) {
                requireEmployed(memberId);
            }
            requireNoActiveReservation(ReservationScopeType.TEAM, teamBusinessId);
            for (String memberId : existingMembers) {
                requireNoActiveReservation(ReservationScopeType.EMPLOYEE, memberId);
            }

            Instant now = clock.instant();
            String operatorId = operatorId();
            if (!repository.updateTeam(teamBusinessId, name, command.expectedRowVersion(), now, operatorId)) {
                throw new ApiException(
                        ApiErrorType.CONCURRENT_CONFLICT, "WORKFORCE_VERSION_CONFLICT",
                        "小组已被其他操作修改，请重新打开后再保存");
            }
            for (String memberId : existingMembers) {
                if (!memberIds.contains(memberId)) {
                    repository.insertTeamMemberFact(teamBusinessId, memberId, "LEAVE", now, operatorId);
                }
            }
            repository.replaceTeamMembers(teamBusinessId, memberIds, now, operatorId);
            recordAudit("UPDATE_TEAM", teamBusinessId, Map.of("teamNo", existing.teamNo()));
            return toRef(requireTeam(teamBusinessId));
        });
    }

    @Override
    public void deactivateTeam(DeactivateTeamCommand command) {
        authorization.require(PermissionCodes.WORKFORCE_TEAM_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_TEAM", "停用命令不能为空");
        }
        String teamBusinessId = requireText(command.teamBusinessId(), "小组标识", 64);

        transactionTemplate.executeWithoutResult(status -> {
            requireTeam(teamBusinessId);
            List<String> members = repository.teamMemberIds(teamBusinessId);
            lockGuardsInOrder(members, List.of(teamBusinessId));
            requireNoActiveReservation(ReservationScopeType.TEAM, teamBusinessId);
            Instant now = clock.instant();
            if (!repository.updateTeamStatus(
                    teamBusinessId, TeamStatus.INACTIVE, command.expectedRowVersion(), now, operatorId())) {
                throw new ApiException(
                        ApiErrorType.CONCURRENT_CONFLICT, "WORKFORCE_VERSION_CONFLICT",
                        "小组已被其他操作修改，请重新打开后再操作");
            }
            recordAudit("DEACTIVATE_TEAM", teamBusinessId, Map.of());
        });
    }

    @Override
    public void updateEfficiencyStandard(UpdateEfficiencyStandardCommand command) {
        authorization.require(PermissionCodes.WORKFORCE_STANDARD_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_STANDARD", "标准不能为空");
        }
        String standardBusinessId = requireText(command.standardBusinessId(), "标准标识", 64);
        if (command.efficiencyFactor() == null || command.efficiencyFactor().compareTo(BigDecimal.ONE) <= 0) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "WORKFORCE_STANDARD_INVALID", "效率因子必须大于 1");
        }
        if (command.memberCount() < 2) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "WORKFORCE_STANDARD_INVALID", "效率标准人数至少为 2");
        }

        transactionTemplate.executeWithoutResult(status -> {
            lockGuardsInOrder(List.of(), List.of());
            reservations.lockGuard(ReservationScopeType.EFFICIENCY_STANDARD, standardBusinessId);
            if (reservations.activeReferenceCount(
                    ReservationScopeType.EFFICIENCY_STANDARD, standardBusinessId) > 0) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "WORKFORCE_STANDARD_IN_USE",
                        "效率标准仍被活动任务引用，不能修改");
            }
            Instant now = clock.instant();
            if (!repository.updateEfficiencyStandard(
                    standardBusinessId, command.memberCount(), command.efficiencyFactor(),
                    command.expectedRowVersion(), now, operatorId())) {
                throw new ApiException(
                        ApiErrorType.CONCURRENT_CONFLICT, "WORKFORCE_VERSION_CONFLICT",
                        "效率标准已被其他操作修改，请重新打开后再保存");
            }
            recordAudit("UPDATE_EFFICIENCY_STANDARD", standardBusinessId, Map.of(
                    "memberCount", String.valueOf(command.memberCount()),
                    "efficiencyFactor", command.efficiencyFactor().toPlainString()));
        });
    }

    @Override
    public void updateWorkTimeStandard(UpdateWorkTimeStandardCommand command) {
        authorization.require(PermissionCodes.WORKFORCE_STANDARD_MANAGE, ResourceScope.GLOBAL);
        if (command == null || command.effectiveMinutes() <= 0) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "WORKFORCE_STANDARD_INVALID", "每日有效分钟必须大于 0");
        }

        transactionTemplate.executeWithoutResult(status -> {
            var daily = repository.findDailyStandard()
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.NOT_FOUND, "WORKFORCE_STANDARD_INVALID", "每日有效时间标准不存在"));
            lockGuardsInOrder(List.of(), List.of());
            reservations.lockGuard(ReservationScopeType.DAILY_WORK_TIME_STANDARD, daily.standardBusinessId());
            if (reservations.activeReferenceCount(
                    ReservationScopeType.DAILY_WORK_TIME_STANDARD, daily.standardBusinessId()) > 0) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "WORKFORCE_STANDARD_IN_USE",
                        "每日有效时间标准仍被活动任务引用，不能修改");
            }
            if (!repository.updateDailyStandard(
                    daily.standardBusinessId(), command.effectiveMinutes(), command.expectedRowVersion(),
                    clock.instant(), operatorId())) {
                throw new ApiException(
                        ApiErrorType.CONCURRENT_CONFLICT, "WORKFORCE_VERSION_CONFLICT",
                        "每日有效时间标准已被其他操作修改，请重新打开后再保存");
            }
            recordAudit("UPDATE_DAILY_WORK_TIME_STANDARD", daily.standardBusinessId(),
                    Map.of("effectiveMinutes", String.valueOf(command.effectiveMinutes())));
        });
    }

    @Override
    public List<EmployeeView> listEmployees() {
        authorization.require(PermissionCodes.WORKFORCE_READ, ResourceScope.GLOBAL);
        return repository.listEmployees(null).stream().map(this::toView).toList();
    }

    @Override
    public EmployeeView getEmployee(String employeeBusinessId) {
        authorization.require(PermissionCodes.WORKFORCE_READ, ResourceScope.GLOBAL);
        return toView(requireEmployee(employeeBusinessId));
    }

    @Override
    public List<TeamView> listTeams() {
        authorization.require(PermissionCodes.WORKFORCE_READ, ResourceScope.GLOBAL);
        return repository.listTeams().stream().map(this::toView).toList();
    }

    @Override
    public TeamView getTeam(String teamBusinessId) {
        authorization.require(PermissionCodes.WORKFORCE_READ, ResourceScope.GLOBAL);
        return toView(requireTeam(teamBusinessId));
    }

    @Override
    public WorkforceStandardView getStandards() {
        authorization.require(PermissionCodes.WORKFORCE_READ, ResourceScope.GLOBAL);
        var daily = repository.findDailyStandard()
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "WORKFORCE_STANDARD_INVALID", "每日有效时间标准不存在"));
        return new WorkforceStandardView(
                daily.standardBusinessId(),
                daily.effectiveMinutes(),
                daily.contentVersion(),
                daily.rowVersion(),
                repository.listEfficiencyStandards().stream()
                        .map(standard -> new WorkforceStandardView.EfficiencyStandardView(
                                standard.standardBusinessId(), standard.memberCount(),
                                standard.efficiencyFactor(), standard.contentVersion(), standard.rowVersion()))
                        .toList());
    }

    @Override
    public AssignmentEligibility checkEligibility(
            AssignmentSubjectRef subject, String workTypeCode, LocalDate workDate) {
        if (subject == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_SUBJECT", "执行主体不能为空");
        }
        if (workDate == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_WORK_DATE", "工作日期不能为空");
        }
        List<String> reasons = new ArrayList<>();
        if (subject.subjectType() == AssignmentSubjectType.EMPLOYEE) {
            repository.findEmployee(subject.businessId())
                    .ifPresentOrElse(
                            employee -> {
                                if (employee.employmentStatus() != EmploymentStatus.EMPLOYED) {
                                    reasons.add("员工已离职");
                                }
                                if (!repository.hasActiveQualification(
                                        subject.businessId(), workTypeCode, workDate)) {
                                    reasons.add("员工缺少该工种的有效资格");
                                }
                            },
                            () -> reasons.add("员工不存在"));
        } else {
            repository.findTeam(subject.businessId())
                    .ifPresentOrElse(
                            team -> {
                                if (team.status() != TeamStatus.ACTIVE) {
                                    reasons.add("小组已停用");
                                }
                                List<String> members = repository.teamMemberIds(subject.businessId());
                                if (members.size() < 2) {
                                    reasons.add("小组至少需要两名成员");
                                }
                                for (String memberId : members) {
                                    var employee = repository.findEmployee(memberId);
                                    if (employee.isEmpty()
                                            || employee.orElseThrow().employmentStatus()
                                                    != EmploymentStatus.EMPLOYED) {
                                        reasons.add("小组成员已离职或不存在的成员：" + memberId);
                                    } else if (!repository.hasActiveQualification(memberId, workTypeCode, workDate)) {
                                        reasons.add("小组成员缺少该工种资格：" + memberId);
                                    }
                                }
                            },
                            () -> reasons.add("小组不存在"));
        }
        return new AssignmentEligibility(subject, workTypeCode, workDate, reasons.isEmpty(), reasons);
    }

    @Override
    public AssignmentSubjectCurrentView getCurrent(AssignmentSubjectRef subject) {
        authorization.require(PermissionCodes.WORKFORCE_READ, ResourceScope.GLOBAL);
        return loadCurrent(subject);
    }

    /** 供快照服务复用：读取执行主体当前视图（调用方已校验权限）。 */
    public AssignmentSubjectCurrentView loadCurrent(AssignmentSubjectRef subject) {
        if (subject.subjectType() == AssignmentSubjectType.EMPLOYEE) {
            EmployeeRecord employee = requireEmployee(subject.businessId());
            return new AssignmentSubjectCurrentView(
                    subject,
                    employee.name(),
                    employee.employmentStatus(),
                    null,
                    repository.listQualifications(subject.businessId()).stream()
                            .map(qualification -> new EmployeeView.QualificationView(
                                    qualification.workTypeCode(), qualification.status(),
                                    qualification.effectiveFrom()))
                            .toList(),
                    List.of(),
                    null,
                    employee.contentVersion());
        }
        TeamRecord team = requireTeam(subject.businessId());
        List<String> members = repository.teamMemberIds(subject.businessId());
        BigDecimal efficiency = repository.findEfficiencyStandardByMemberCount(members.size())
                .map(WorkforceRepository.EfficiencyStandardRecord::efficiencyFactor)
                .orElse(null);
        return new AssignmentSubjectCurrentView(
                subject,
                team.name(),
                null,
                team.status(),
                List.of(),
                members,
                efficiency,
                team.contentVersion());
    }

    /** 必须在事务内调用。 */
    private void applyQualificationChange(
            ChangeQualificationCommand command, QualificationStatus target, String employeeBusinessId) {
        String workTypeCode = requireText(command.workTypeCode(), "工种代码", 32);
        LocalDate effectiveDate = command.effectiveDate() == null ? today() : command.effectiveDate();
        {
            EmployeeRecord employee = requireEmployee(employeeBusinessId);
            if (employee.employmentStatus() != EmploymentStatus.EMPLOYED) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "EMPLOYEE_LEFT", "员工已离职，不能变更工种资格");
            }
            if (!repository.workTypeExists(workTypeCode)) {
                throw new ApiException(
                        ApiErrorType.VALIDATION, "UNKNOWN_WORK_TYPE", "工种不存在：" + workTypeCode);
            }
            repository.upsertQualification(
                    employeeBusinessId, workTypeCode, target, effectiveDate, clock.instant(), operatorId());
            repository.insertQualificationFact(
                    employeeBusinessId, workTypeCode,
                    target == QualificationStatus.ACTIVE ? "GRANT" : "REVOKE",
                    effectiveDate, clock.instant(), operatorId());
            recordAudit(
                    target == QualificationStatus.ACTIVE ? "GRANT_WORK_TYPE" : "REVOKE_WORK_TYPE",
                    employeeBusinessId, Map.of("workTypeCode", workTypeCode));
        }
    }

    /** 统一锁序：先按稳定标识排序锁员工守卫，再锁小组守卫。 */
    private void lockGuardsInOrder(List<String> employeeIds, List<String> teamIds) {
        employeeIds.stream().sorted().distinct()
                .forEach(id -> reservations.lockGuard(ReservationScopeType.EMPLOYEE, id));
        teamIds.stream().sorted().distinct()
                .forEach(id -> reservations.lockGuard(ReservationScopeType.TEAM, id));
    }

    private void lockGuardAndRequireNoActive(ReservationScopeType guardType, String businessId) {
        reservations.lockGuard(guardType, businessId);
        requireNoActiveReservation(guardType, businessId);
    }

    private void requireNoActiveReservation(ReservationScopeType guardType, String businessId) {
        int active = reservations.activeReferenceCount(guardType, businessId);
        if (active > 0) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "WORKFORCE_ACTIVITY_REFERENCED",
                    "仍存在活动任务引用，不能变更可用能力：" + businessId);
        }
    }

    private List<String> validateMembers(Set<String> memberIds) {
        if (memberIds == null || memberIds.size() < 2) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "WORKFORCE_TEAM_INVALID", "小组至少需要两名当前在职员工");
        }
        List<String> ordered = memberIds.stream().map(id -> requireText(id, "员工标识", 64)).sorted().toList();
        if (ordered.size() != memberIds.size()) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "WORKFORCE_TEAM_INVALID", "小组成员不能重复");
        }
        return ordered;
    }

    private void requireEmployed(String employeeBusinessId) {
        EmployeeRecord employee = requireEmployee(employeeBusinessId);
        if (employee.employmentStatus() != EmploymentStatus.EMPLOYED) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "EMPLOYEE_LEFT",
                    "员工已离职，不能加入小组：" + employee.employeeNo());
        }
    }

    private EmployeeRecord requireEmployee(String employeeBusinessId) {
        return repository.findEmployee(employeeBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "EMPLOYEE_NOT_FOUND", "员工不存在"));
    }

    private TeamRecord requireTeam(String teamBusinessId) {
        return repository.findTeam(teamBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "WORKFORCE_TEAM_NOT_FOUND", "小组不存在"));
    }

    private String operatorId() {
        return currentActor.requireCurrentActor().accountBusinessId().value();
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC);
    }

    private void recordAudit(String actionCode, String targetBusinessId, Map<String, String> detail) {
        audit.record(new RecordAuditCommand(
                RequestCorrelationHolder.getOrUnknown(),
                operatorId(),
                "workforce",
                actionCode,
                "WORKFORCE",
                targetBusinessId,
                "SUCCESS",
                new LinkedHashMap<>(detail),
                clock.instant()));
    }

    static String requireText(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_FIELD", label + "不能为空");
        }
        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "FIELD_TOO_LONG", label + "长度不能超过 " + maxLength);
        }
        if (CONTROL_CHARS.matcher(stripped).find()) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "INVALID_CHARACTERS", label + "不能包含换行或控制字符");
        }
        return stripped;
    }

    private static String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String stripped = phone.strip();
        if (!PHONE_ALLOWED.matcher(stripped).matches()) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "INVALID_PHONE", "联系电话只能包含数字、加号、括号、空格和短横线");
        }
        return stripped;
    }

    private static EmployeeRef toRef(EmployeeRecord record) {
        return new EmployeeRef(
                record.employeeBusinessId(), record.employeeNo(), record.employmentStatus(),
                record.contentVersion(), record.rowVersion());
    }

    private static TeamRef toRef(TeamRecord record) {
        return new TeamRef(
                record.teamBusinessId(), record.teamNo(), record.status(),
                record.contentVersion(), record.rowVersion());
    }

    private EmployeeView toView(EmployeeRecord record) {
        return new EmployeeView(
                record.employeeBusinessId(),
                record.employeeNo(),
                record.name(),
                record.phone(),
                record.employmentStatus(),
                repository.latestEffectiveDate(record.employeeBusinessId()).orElse(null),
                record.contentVersion(),
                record.rowVersion(),
                repository.listQualifications(record.employeeBusinessId()).stream()
                        .map(qualification -> new EmployeeView.QualificationView(
                                qualification.workTypeCode(), qualification.status(),
                                qualification.effectiveFrom()))
                        .toList(),
                record.updatedAt());
    }

    private TeamView toView(TeamRecord record) {
        List<String> memberIds = repository.teamMemberIds(record.teamBusinessId());
        BigDecimal efficiency = repository.findEfficiencyStandardByMemberCount(memberIds.size())
                .map(standard -> standard.efficiencyFactor())
                .orElse(null);
        long efficiencyVersion = repository.findEfficiencyStandardByMemberCount(memberIds.size())
                .map(standard -> standard.contentVersion())
                .orElse(0L);
        return new TeamView(
                record.teamBusinessId(),
                record.teamNo(),
                record.name(),
                record.status(),
                record.contentVersion(),
                record.rowVersion(),
                memberIds.stream().map(memberId -> {
                    var employee = repository.findEmployee(memberId);
                    return new TeamView.TeamMemberView(
                            memberId,
                            employee.map(EmployeeRecord::employeeNo).orElse(null),
                            employee.map(EmployeeRecord::name).orElse(null),
                            employee.map(EmployeeRecord::employmentStatus).orElse(null));
                }).toList(),
                efficiency,
                efficiencyVersion);
    }
}
