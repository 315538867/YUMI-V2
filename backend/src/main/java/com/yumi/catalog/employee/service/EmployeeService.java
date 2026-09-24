package com.yumi.catalog.employee.service;

import com.yumi.catalog.changelog.MasterDataChangeLogService;
import com.yumi.catalog.employee.dto.EmployeeResponse;
import com.yumi.catalog.employee.dto.WorkTypeView;
import com.yumi.catalog.employee.internal.Employee;
import com.yumi.catalog.employee.internal.EmployeeEmploymentEvent;
import com.yumi.catalog.employee.internal.EmployeeEmploymentEventRepository;
import com.yumi.catalog.employee.internal.EmployeeRepository;
import com.yumi.catalog.employee.internal.EmployeeWorkType;
import com.yumi.catalog.employee.internal.EmployeeWorkTypeRepository;
import com.yumi.catalog.employee.internal.WorkTypeReference;
import com.yumi.identity.AuditContext;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import com.yumi.shared.numbering.SequenceAllocator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 员工档案应用服务：创建（编号+HIRE 事件）、查询、乐观锁编辑（工种整体替换）、
 * 离职/重入职状态机，写后统一落主数据变更日志。
 */
@Service
public class EmployeeService {

    private static final String SEQUENCE_KEY = "employees";
    private static final String CHANGE_LOG_TYPE = "EMPLOYEE";

    private final EmployeeRepository employees;
    private final EmployeeWorkTypeRepository workTypes;
    private final WorkTypeReference workTypeReference;
    private final EmployeeEmploymentEventRepository events;
    private final SequenceAllocator sequenceAllocator;
    private final AuditContext auditContext;
    private final MasterDataChangeLogService changeLog;

    public EmployeeService(EmployeeRepository employees, EmployeeWorkTypeRepository workTypes,
                           WorkTypeReference workTypeReference, EmployeeEmploymentEventRepository events,
                           SequenceAllocator sequenceAllocator, AuditContext auditContext,
                           MasterDataChangeLogService changeLog) {
        this.employees = employees;
        this.workTypes = workTypes;
        this.workTypeReference = workTypeReference;
        this.events = events;
        this.sequenceAllocator = sequenceAllocator;
        this.auditContext = auditContext;
        this.changeLog = changeLog;
    }

    /** 创建请求：name/firstHireDate/workTypes 必填（Bean Validation），其余可选。 */
    public record CreateCommand(String name, String phone, LocalDate firstHireDate, String note,
                                List<String> workTypes) {
    }

    /** 编辑请求：version 必带；status 与 firstHireDate 不在映射范围内（创建后不可变/派生）。 */
    public record PatchCommand(Long version, String name, String phone, String note, List<String> workTypes) {
    }

    /** 离职请求：reason 必填为派生决策，date=事件日期。 */
    public record LeaveCommand(String reason, LocalDate date) {
    }

    /** 重入职请求：仅事件日期；first_hire_date 保持首次值。 */
    public record RehireCommand(LocalDate date) {
    }

    @Transactional
    public EmployeeResponse create(CreateCommand command) {
        validateWorkTypes(command.workTypes());
        var audit = auditContext.current();
        var employee = new Employee();
        employee.setEmployeeNo(SequenceAllocator.format("E", sequenceAllocator.next(SEQUENCE_KEY)));
        employee.setName(command.name());
        employee.setPhone(command.phone());
        employee.setStatus(Employee.STATUS_ACTIVE);
        employee.setFirstHireDate(command.firstHireDate());
        employee.setNote(command.note());
        employee.setRequestId(audit.requestId());
        employee.setIdempotencyKey(audit.idempotencyKey());
        var saved = employees.save(employee);
        var types = normalize(command.workTypes());
        for (var typeId : resolveWorkTypeIds(types)) {
            workTypes.save(new EmployeeWorkType(saved.getId(), typeId, audit.requestId()));
        }
        events.save(new EmployeeEmploymentEvent(saved.getId(), EmployeeEmploymentEvent.TYPE_HIRE,
                command.firstHireDate(), null, audit.requestId()));
        return toResponse(saved, types);
    }

    @Transactional(readOnly = true)
    public List<EmployeeResponse> list(String status, String name) {
        var hasStatus = status != null && !status.isBlank();
        var hasName = name != null && !name.isBlank();
        List<Employee> found;
        if (hasStatus && hasName) {
            found = employees.findByStatusAndNameLikeOrderByEmployeeNoAsc(status, "%" + name + "%");
        } else if (hasStatus) {
            found = employees.findByStatusOrderByEmployeeNoAsc(status);
        } else if (hasName) {
            found = employees.findByNameLikeOrderByEmployeeNoAsc("%" + name + "%");
        } else {
            found = employees.findAllByOrderByEmployeeNoAsc();
        }
        return found.stream().map(employee -> toResponse(employee, typesOf(employee.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public EmployeeResponse get(Long id) {
        var employee = require(id);
        return toResponse(employee, typesOf(id));
    }

    @Transactional
    public EmployeeResponse patch(Long id, PatchCommand command) {
        if (command.version() == null) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "version 必填",
                    List.of(new ApiFieldError("version", "version 必填")));
        }
        var audit = auditContext.current();
        var employee = require(id);
        if (!employee.getVersion().equals(command.version())) {
            throw new ApiException(ErrorCode.CONFLICT_VERSION);
        }
        var before = snapshot(employee, typesOf(id));
        if (command.name() != null) {
            if (command.name().isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_INVALID, "姓名不能为空",
                        List.of(new ApiFieldError("name", "姓名不能为空")));
            }
            employee.setName(command.name());
        }
        if (command.phone() != null) {
            employee.setPhone(command.phone());
        }
        if (command.note() != null) {
            employee.setNote(command.note());
        }
        var afterTypes = typesOf(id);
        if (command.workTypes() != null) {
            validateWorkTypes(command.workTypes());
            afterTypes = normalize(command.workTypes());
            workTypes.deleteByEmployeeId(id);
            for (var typeId : resolveWorkTypeIds(afterTypes)) {
                workTypes.save(new EmployeeWorkType(id, typeId, audit.requestId()));
            }
        }
        employee.setRequestId(audit.requestId());
        employee.setIdempotencyKey(audit.idempotencyKey());
        var saved = employees.saveAndFlush(employee);
        changeLog.record(CHANGE_LOG_TYPE, id, saved.getEmployeeNo(), before,
                snapshot(saved, afterTypes), null, audit.adminUsername(), audit.requestId());
        return toResponse(saved, afterTypes);
    }

    @Transactional
    public EmployeeResponse leave(Long id, LeaveCommand command) {
        var audit = auditContext.current();
        var employee = require(id);
        if (Employee.STATUS_LEFT.equals(employee.getStatus())) {
            throw new ApiException(ErrorCode.STATE_NOT_EDITABLE, "员工已离职");
        }
        var types = typesOf(id);
        var before = snapshot(employee, types);
        employee.setStatus(Employee.STATUS_LEFT);
        employee.setRequestId(audit.requestId());
        employee.setIdempotencyKey(audit.idempotencyKey());
        var saved = employees.saveAndFlush(employee);
        events.save(new EmployeeEmploymentEvent(id, EmployeeEmploymentEvent.TYPE_LEAVE,
                command.date(), command.reason(), audit.requestId()));
        changeLog.record(CHANGE_LOG_TYPE, id, saved.getEmployeeNo(), before,
                snapshot(saved, types), command.reason(), audit.adminUsername(), audit.requestId());
        return toResponse(saved, types);
    }

    @Transactional
    public EmployeeResponse rehire(Long id, RehireCommand command) {
        var audit = auditContext.current();
        var employee = require(id);
        if (!Employee.STATUS_LEFT.equals(employee.getStatus())) {
            throw new ApiException(ErrorCode.STATE_NOT_EDITABLE, "员工未处于离职状态");
        }
        var types = typesOf(id);
        var before = snapshot(employee, types);
        employee.setStatus(Employee.STATUS_ACTIVE);
        employee.setRequestId(audit.requestId());
        employee.setIdempotencyKey(audit.idempotencyKey());
        var saved = employees.saveAndFlush(employee);
        events.save(new EmployeeEmploymentEvent(id, EmployeeEmploymentEvent.TYPE_REHIRE,
                command.date(), null, audit.requestId()));
        changeLog.record(CHANGE_LOG_TYPE, id, saved.getEmployeeNo(), before,
                snapshot(saved, types), null, audit.adminUsername(), audit.requestId());
        return toResponse(saved, types);
    }

    private Employee require(Long id) {
        return employees.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "员工不存在"));
    }

    /** 员工当前的工种 code（存储为 work_type_id，对外按系统固定 code）。 */
    private List<String> typesOf(Long employeeId) {
        var ids = workTypes.findByEmployeeIdOrderByIdAsc(employeeId).stream()
                .map(EmployeeWorkType::getWorkTypeId).toList();
        var catalog = workTypeReference.byIds(ids);
        return ids.stream().map(catalog::get).filter(Objects::nonNull)
                .map(WorkTypeReference.WorkType::code).toList();
    }

    /** 工种 code → id（调用前必须先 validateWorkTypes）。 */
    private List<Long> resolveWorkTypeIds(List<String> codes) {
        return codes.stream()
                .map(code -> workTypeReference.byCode(code).orElseThrow())
                .map(WorkTypeReference.WorkType::id)
                .toList();
    }

    /** 工种 code → 对外视图（code + 当前名称）。 */
    private List<WorkTypeView> workTypeViews(List<String> codes) {
        return codes.stream()
                .map(code -> workTypeReference.byCode(code).orElse(null))
                .filter(Objects::nonNull)
                .map(type -> new WorkTypeView(type.code(), type.name()))
                .toList();
    }

    private void validateWorkTypes(List<String> types) {
        if (types == null) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "工种不能为空",
                    List.of(new ApiFieldError("workTypes", "工种不能为空")));
        }
        var invalid = types.stream().filter(type -> workTypeReference.byCode(type).isEmpty()).toList();
        if (!invalid.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "工种取值不合法",
                    List.of(new ApiFieldError("workTypes", "仅支持系统内置工种")));
        }
    }

    private List<String> normalize(List<String> types) {
        return List.copyOf(new LinkedHashSet<>(types));
    }

    private Map<String, Object> snapshot(Employee employee, List<String> types) {
        return Map.of(
                "employeeNo", employee.getEmployeeNo(),
                "name", employee.getName(),
                "phone", employee.getPhone() == null ? "" : employee.getPhone(),
                "note", employee.getNote() == null ? "" : employee.getNote(),
                "status", employee.getStatus(),
                "firstHireDate", employee.getFirstHireDate(),
                "version", employee.getVersion(),
                "workTypes", types);
    }

    private EmployeeResponse toResponse(Employee employee, List<String> types) {
        return new EmployeeResponse(employee.getId(), employee.getEmployeeNo(), employee.getName(),
                employee.getPhone(), employee.getStatus(), employee.getFirstHireDate(), employee.getNote(),
                workTypeViews(types), employee.getVersion());
    }
}
