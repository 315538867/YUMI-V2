package com.yumi.production.otherschedule;

import com.yumi.catalog.employee.dto.EmployeeSnapshot;
import com.yumi.catalog.employee.service.EmployeeEligibilityService;
import com.yumi.identity.AuditContext;
import com.yumi.production.otherschedule.internal.OtherScheduleRepository;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import com.yumi.shared.numbering.SequenceAllocator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 其他排班应用服务（任务 5.13，施工文档 §3.8–§3.10/§4.7）。
 *
 * 口径：以**小时 + 0–59 分钟**录入，`总分钟 = 小时 × 60 + 分钟` 且必须大于 0；一次性工时核验；
 * 已核验后录错用**工时更正**追加事实（原核验不动，有效工时取最新更正）；
 * **不产生商品、库存或订单履约事实**，也不占用任何待安排来源。
 * 其他排班只校验员工**在职**（不绑定工序，工种校验无意义，见施工文档 §11）。
 */
@Service
public class OtherScheduleService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_VERIFIED = "VERIFIED";

    private final OtherScheduleRepository repository;
    private final EmployeeEligibilityService eligibility;
    private final SequenceAllocator sequenceAllocator;
    private final AuditContext auditContext;

    public OtherScheduleService(OtherScheduleRepository repository, EmployeeEligibilityService eligibility,
                                SequenceAllocator sequenceAllocator, AuditContext auditContext) {
        this.repository = repository;
        this.eligibility = eligibility;
        this.sequenceAllocator = sequenceAllocator;
        this.auditContext = auditContext;
    }

    public List<OtherScheduleViews.OtherScheduleView> list(LocalDate dateFrom, LocalDate dateTo, Long employeeId,
                                                           String status) {
        return repository.find(dateFrom, dateTo, employeeId, status).stream().map(this::toView).toList();
    }

    public OtherScheduleViews.OtherScheduleView get(long id) {
        return toView(repository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "其他排班不存在")));
    }

    @Transactional
    public OtherScheduleViews.OtherScheduleView create(
            OtherScheduleViews.CreateOtherScheduleRequest request) {
        var errors = new ArrayList<ApiFieldError>();
        if (request.scheduleDate() == null) {
            errors.add(new ApiFieldError("scheduleDate", "排班日期必填"));
        }
        if (request.employeeId() == null) {
            errors.add(new ApiFieldError("employeeId", "员工必填"));
        }
        int total = totalMinutes(request.hours(), request.minutes(), errors);
        failIfInvalid(errors);

        EmployeeSnapshot employee = eligibility.requireActive(request.employeeId());
        var audit = auditContext.current();
        var scheduleNo = SequenceAllocator.format("OS", sequenceAllocator.next("other_schedules"), 6);
        long id = repository.insert(scheduleNo, request.scheduleDate(), request.employeeId(), employee.name(),
                request.hours(), request.minutes(), total, request.note(), audit.requestId());
        return get(id);
    }

    /** 一次性工时核验：只能核验一次，核验后不可再核验。 */
    @Transactional
    public OtherScheduleViews.OtherScheduleView verify(long id, OtherScheduleViews.VerifyOtherScheduleRequest request) {
        var schedule = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "其他排班不存在"));
        requireStatus(schedule, STATUS_PENDING, "只有待执行的排班可以核验");
        var errors = new ArrayList<ApiFieldError>();
        int total = totalMinutes(request.hours(), request.minutes(), errors);
        failIfInvalid(errors);

        var audit = auditContext.current();
        repository.insertVerification(id, total, audit.adminUsername(), audit.requestId());
        repository.markVerified(id, audit.requestId());
        return get(id);
    }

    @Transactional
    public OtherScheduleViews.OtherScheduleView cancel(long id, OtherScheduleViews.CancelOtherScheduleRequest request) {
        var schedule = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "其他排班不存在"));
        requireStatus(schedule, STATUS_PENDING, "只有待执行的排班可以取消");
        if (request.reason() == null || request.reason().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "取消原因必填",
                    List.of(new ApiFieldError("reason", "请填写取消原因")));
        }
        var audit = auditContext.current();
        repository.markCancelled(id, audit.adminUsername(), request.reason(), audit.requestId());
        return get(id);
    }

    /** 工时更正：已核验排班录错时追加更正事实，原核验不变。 */
    @Transactional
    public OtherScheduleViews.OtherScheduleView correct(long id,
                                                        OtherScheduleViews.CorrectOtherScheduleRequest request) {
        var schedule = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "其他排班不存在"));
        requireStatus(schedule, STATUS_VERIFIED, "只有已核验的排班可以更正工时");
        if (request.reason() == null || request.reason().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "更正原因必填",
                    List.of(new ApiFieldError("reason", "请填写更正原因")));
        }
        var errors = new ArrayList<ApiFieldError>();
        int total = totalMinutes(request.hours(), request.minutes(), errors);
        failIfInvalid(errors);

        var verification = repository.findVerification(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "工时核验不存在"));
        Integer before = repository.effectiveMinutes(id);
        var audit = auditContext.current();
        repository.insertCorrection(verification.id(), id, before == null ? 0 : before, total, request.reason(),
                audit.requestId());
        return get(id);
    }

    /** 总分钟 = 小时 × 60 + 分钟；分钟 0–59，总分钟必须大于 0。 */
    private static int totalMinutes(Integer hours, Integer minutes, List<ApiFieldError> errors) {
        if (hours == null || hours < 0) {
            errors.add(new ApiFieldError("hours", "小时必须不小于 0"));
        }
        if (minutes == null || minutes < 0 || minutes > 59) {
            errors.add(new ApiFieldError("minutes", "分钟必须在 0 与 59 之间"));
        }
        if (!errors.isEmpty()) {
            return 0;
        }
        int total = hours * 60 + minutes;
        if (total <= 0) {
            errors.add(new ApiFieldError("hours", "总分钟必须大于 0"));
        }
        return total;
    }

    private static void requireStatus(OtherScheduleRepository.OtherScheduleRow schedule, String expected,
                                      String message) {
        if (!expected.equals(schedule.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_CANCELABLE, message,
                    List.of(new ApiFieldError("scheduleId", "当前状态 " + schedule.status())));
        }
    }

    private OtherScheduleViews.OtherScheduleView toView(OtherScheduleRepository.OtherScheduleRow row) {
        var verification = repository.findVerification(row.id()).orElse(null);
        Integer effective = repository.effectiveMinutes(row.id());
        boolean corrected = effective != null && verification != null
                && effective != verification.totalMinutes();
        return new OtherScheduleViews.OtherScheduleView(row.id(), row.scheduleNo(), row.scheduleDate(),
                row.employeeId(), row.employeeName(), row.hours(), row.minutes(), row.totalMinutes(),
                row.status(), row.note(), verification == null ? null : verification.totalMinutes(), effective,
                corrected, row.cancelReason(), row.version());
    }

    private static void failIfInvalid(List<ApiFieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID,
                    ErrorCode.VALIDATION_INVALID.defaultMessage(), errors);
        }
    }
}
