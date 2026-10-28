package com.yumi.production.application;

import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.production.api.ChangeDefectReasonStatusCommand;
import com.yumi.production.api.DefectReasonAdministrationService;
import com.yumi.production.api.DefectReasonQuery;
import com.yumi.production.api.DefectReasonStatus;
import com.yumi.production.api.DefectReasonView;
import com.yumi.production.api.SaveDefectReasonCommand;
import com.yumi.production.application.port.DispositionRepository;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 缺陷原因维护（基线 11 §7.6）。
 *
 * <p>停用只影响新核验；历史缺陷事实保存核验时的原因编号与名称，继续展示。
 */
public class DefectReasonAdministrationServiceImpl
        implements DefectReasonAdministrationService, DefectReasonQuery {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");

    private final DispositionRepository repository;
    private final AuthorizationService authorization;

    public DefectReasonAdministrationServiceImpl(
            DispositionRepository repository, AuthorizationService authorization) {
        this.repository = repository;
        this.authorization = authorization;
    }

    @Override
    public DefectReasonView save(SaveDefectReasonCommand command) {
        authorization.require(PermissionCodes.PRODUCTION_DEFECT_REASON_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_DEFECT_REASON", "缺陷原因不能为空");
        }
        String name = command.name().strip();
        String code = command.defectReasonCode() == null || command.defectReasonCode().isBlank()
                ? defaultCode(name)
                : command.defectReasonCode().strip().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCTION_DEFECT_INVALID",
                    "缺陷原因标识只能使用大写字母、数字与下划线");
        }
        var existing = repository.findDefectReason(code);
        if (existing.isEmpty()) {
            repository.insertDefectReason(
                    code, name, DefectReasonStatus.ACTIVE, command.other(),
                    command.applicableProcess(), command.requiresText());
            return toView(code);
        }
        if (command.expectedRowVersion() <= 0) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCTION_DEFECT_INVALID", "修改缺陷原因必须携带行版本");
        }
        if (!repository.updateDefectReason(
                code, name, command.other(), command.applicableProcess(),
                command.requiresText(), command.expectedRowVersion())) {
            throw new ApiException(
                    ApiErrorType.CONCURRENT_CONFLICT, "PRODUCTION_DEFECT_REASON_CONFLICT",
                    "缺陷原因已被其他操作修改，请刷新后重试");
        }
        return toView(code);
    }

    @Override
    public DefectReasonView changeStatus(ChangeDefectReasonStatusCommand command) {
        authorization.require(PermissionCodes.PRODUCTION_DEFECT_REASON_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_DEFECT_REASON", "缺陷原因不能为空");
        }
        String code = command.defectReasonCode().strip();
        repository.findDefectReason(code)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_DEFECT_INVALID", "缺陷原因不存在：" + code));
        if (!repository.updateDefectReasonStatus(code, command.status(), command.expectedRowVersion())) {
            throw new ApiException(
                    ApiErrorType.CONCURRENT_CONFLICT, "PRODUCTION_DEFECT_REASON_CONFLICT",
                    "缺陷原因已被其他操作修改，请刷新后重试");
        }
        return toView(code);
    }

    @Override
    public List<DefectReasonView> listAll() {
        authorization.require(PermissionCodes.PRODUCTION_READ, ResourceScope.GLOBAL);
        return repository.findAllDefectReasons().stream().map(DispositionRepository::toView).toList();
    }

    @Override
    public List<DefectReasonView> listActive() {
        authorization.require(PermissionCodes.PRODUCTION_READ, ResourceScope.GLOBAL);
        return repository.findActiveDefectReasons().stream().map(DispositionRepository::toView).toList();
    }

    private DefectReasonView toView(String code) {
        return repository.findDefectReason(code)
                .map(DispositionRepository::toView)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_DEFECT_INVALID", "缺陷原因不存在"));
    }

    /** 由名称推导稳定标识；仅用于新增时未显式给出标识的场景。 */
    private static String defaultCode(String name) {
        String normalized = name.strip().toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
        String code = normalized.replaceAll("[^A-Z0-9_]", "");
        if (code.isEmpty()) {
            code = "DEFECT_" + Math.abs(name.hashCode());
        }
        return code.length() > 32 ? code.substring(0, 32) : code;
    }
}
