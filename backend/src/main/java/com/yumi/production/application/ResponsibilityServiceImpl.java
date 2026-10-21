package com.yumi.production.application;

import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.production.api.CorrectResponsibilityCommand;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.ResponsibilityApplicationService;
import com.yumi.production.api.ResponsibilityType;
import com.yumi.production.api.ResponsibilityView;
import com.yumi.production.application.port.DispositionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 报废责任实现（基线 11 §7.6）。
 *
 * <p>责任数量等于所关联报废明细数量，与去向无关；更正只替换责任主体或免除结论，
 * 发布 REVERSE 旧链 + APPLY 新链，物理退出与数量投影保持不变。
 */
public class ResponsibilityServiceImpl implements ResponsibilityApplicationService {

    private final DispositionRepository repository;
    private final AuthorizationService authorization;
    private final TransactionTemplate transactionTemplate;

    public ResponsibilityServiceImpl(
            DispositionRepository repository,
            AuthorizationService authorization,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.authorization = authorization;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public ResponsibilityView correct(CorrectResponsibilityCommand command) {
        authorization.require(PermissionCodes.PRODUCTION_RESPONSIBILITY_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_RESPONSIBILITY", "责任命令不能为空");
        }
        return transactionTemplate.execute(status -> doCorrect(command));
    }

    @Override
    public ResponsibilityView findByDisposition(String dispositionFactBusinessId) {
        authorization.require(PermissionCodes.PRODUCTION_READ, ResourceScope.GLOBAL);
        var row = repository.findResponsibilityByDisposition(dispositionFactBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_RESPONSIBILITY_NOT_FOUND", "该报废处置没有有效责任"));
        return toView(row);
    }

    private ResponsibilityView doCorrect(CorrectResponsibilityCommand command) {
        var dispositions = repository.findDispositionsOf(command.dispositionFactBusinessId());
        if (dispositions.isEmpty()) {
            throw new ApiException(
                    ApiErrorType.NOT_FOUND, "PRODUCTION_DISPOSITION_NOT_FOUND", "报废处置不存在");
        }
        var disposition = dispositions.getFirst();
        if (disposition.type() != com.yumi.production.api.DefectDispositionType.SCRAPPED) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_RESPONSIBILITY_NOT_APPLICABLE",
                    "返工不生成扣款责任，只有报废处置可以认定责任");
        }
        var current = repository.findResponsibilityByDisposition(command.dispositionFactBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_RESPONSIBILITY_NOT_FOUND",
                        "该报废处置没有有效责任"));
        if (command.expectedRowVersion() != current.rowVersion()) {
            throw new ApiException(
                    ApiErrorType.CONCURRENT_CONFLICT, "PRODUCTION_RESPONSIBILITY_CONFLICT",
                    "责任已被其他操作修改，请刷新后重试");
        }

        // REVERSE 旧链
        repository.insertRevision(new DispositionRepository.RevisionRow(
                "RVN-" + UUID.randomUUID(), current.responsibilityChainBusinessId(),
                command.dispositionFactBusinessId(), "REVERSE", current.responsibilityType(),
                current.responsibleSubjectBusinessId(), current.waivedReason(), current.quantity(),
                command.reasonText(), 0L));
        repository.invalidateResponsibility(current.responsibilityChainBusinessId());

        // APPLY 新链：数量、日期、去向不变
        String newChainId = "RSP-" + UUID.randomUUID();
        repository.insertResponsibility(new DispositionRepository.ResponsibilityRow(
                newChainId, command.dispositionFactBusinessId(), current.terminalExitBusinessId(),
                command.responsibilityType(), command.responsibleSubjectBusinessId(),
                command.waivedReason(), current.quantity(), current.businessDate(), 1L));
        repository.insertRevision(new DispositionRepository.RevisionRow(
                "RVN-" + UUID.randomUUID(), newChainId, command.dispositionFactBusinessId(), "APPLY",
                command.responsibilityType(), command.responsibleSubjectBusinessId(),
                command.waivedReason(), current.quantity(), command.reasonText(), 0L));

        return repository.findResponsibilityByDisposition(command.dispositionFactBusinessId())
                .map(ResponsibilityServiceImpl::toView)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_RESPONSIBILITY_NOT_FOUND",
                        "责任写入后无法读取"));
    }

    static ResponsibilityView toView(DispositionRepository.ResponsibilityRow row) {
        List<ResponsibilityView.RevisionView> revisions = List.of();
        return new ResponsibilityView(
                row.responsibilityChainBusinessId(), row.dispositionFactBusinessId(),
                row.terminalExitBusinessId(), row.responsibilityType(),
                row.responsibleSubjectBusinessId(), row.waivedReason(), row.quantity(),
                row.businessDate(), row.rowVersion(), revisions);
    }

    /** 责任数量与实物去向无关的显式断言，供调用方在更正前后比较。 */
    static boolean hasPhysicalChange(
            PhysicalDestination before, PhysicalDestination after, int quantityBefore, int quantityAfter) {
        return before != after || quantityBefore != quantityAfter;
    }

    /** 责任类型是否属于“免除”。 */
    static boolean isWaived(ResponsibilityType type) {
        return type == ResponsibilityType.WAIVED;
    }
}
