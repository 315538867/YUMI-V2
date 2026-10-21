package com.yumi.production.application;

import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.production.api.DefectDispositionType;
import com.yumi.production.api.DispositionApplicationService;
import com.yumi.production.api.DispositionQuery;
import com.yumi.production.api.DispositionView;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.application.port.DispositionRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 处置与追溯实现（基线 11 §7.6、§7.7）。
 *
 * <p>关键不变量：
 * <ul>
 *   <li>每条报废处置只有一次终态退出，重复确认幂等返回同一退出；</li>
 *   <li>返工不生成责任，只关联返工来源；</li>
 *   <li>缺陷、处置、退出与责任分别存储、分别追溯，互不覆盖。</li>
 * </ul>
 */
public class DispositionServiceImpl implements DispositionApplicationService, DispositionQuery {

    private final DispositionRepository repository;
    private final AuthorizationService authorization;
    private final TransactionTemplate transactionTemplate;

    public DispositionServiceImpl(
            DispositionRepository repository,
            AuthorizationService authorization,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.authorization = authorization;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public String ensureTerminalExit(String dispositionFactBusinessId) {
        authorization.require(PermissionCodes.PRODUCTION_DISPOSE, ResourceScope.GLOBAL);
        if (dispositionFactBusinessId == null || dispositionFactBusinessId.isBlank()) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_DISPOSITION", "处置标识不能为空");
        }
        return transactionTemplate.execute(status -> {
            var dispositions = repository.findDispositionsOf(dispositionFactBusinessId);
            if (dispositions.isEmpty()) {
                throw new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_DISPOSITION_NOT_FOUND", "处置不存在");
            }
            var disposition = dispositions.getFirst();
            if (disposition.type() == DefectDispositionType.REWORK_REQUIRED) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_DISPOSITION_NOT_APPLICABLE",
                        "返工处置不产生终态退出");
            }
            String exitId = disposition.terminalExitBusinessId();
            if (exitId == null || exitId.isBlank()) {
                throw new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_TERMINAL_EXIT_MISSING",
                        "报废处置缺少终态退出，数据不完整");
            }
            // 同一条退出不能被其他处置再次引用：唯一退出只退出一次
            int referencing = repository.countTerminalExits(dispositionFactBusinessId);
            if (referencing != 1) {
                throw new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_TERMINAL_EXIT_DUPLICATED",
                        "同一报废处置存在多次来源退出");
            }
            repository.findTerminalExit(exitId)
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_TERMINAL_EXIT_MISSING",
                            "终态退出记录不存在：" + exitId));
            return exitId;
        });
    }

    @Override
    public DispositionView trace(String taskItemBusinessId) {
        authorization.require(PermissionCodes.PRODUCTION_READ, ResourceScope.GLOBAL);
        var context = repository.findItemContext(taskItemBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_TASK_ITEM_NOT_FOUND", "任务明细不存在"));
        return new DispositionView(
                context.taskItemBusinessId(), context.demandBusinessId(),
                context.productionSourceBusinessId(), defectsOf(taskItemBusinessId));
    }

    @Override
    public List<DispositionView> findByTaskItem(String taskItemBusinessId) {
        return List.of(trace(taskItemBusinessId));
    }

    @Override
    public List<DispositionView> findBySource(String productionSourceBusinessId) {
        authorization.require(PermissionCodes.PRODUCTION_READ, ResourceScope.GLOBAL);
        List<DispositionView> views = new ArrayList<>();
        for (String itemId : repository.findItemIdsOfSource(productionSourceBusinessId)) {
            var context = repository.findItemContext(itemId);
            context.ifPresent(value -> views.add(new DispositionView(
                    value.taskItemBusinessId(), value.demandBusinessId(),
                    value.productionSourceBusinessId(), defectsOf(itemId))));
        }
        return views;
    }

    private List<DispositionView.DefectTrace> defectsOf(String taskItemBusinessId) {
        List<DispositionView.DefectTrace> traces = new ArrayList<>();
        for (var defect : repository.findDefects(taskItemBusinessId)) {
            List<DispositionView.DispositionTrace> dispositions = new ArrayList<>();
            int rework = 0;
            int scrapped = 0;
            for (var disposition : repository.findDispositions(defect.defectFactBusinessId())) {
                if (disposition.type() == DefectDispositionType.REWORK_REQUIRED) {
                    rework += disposition.quantity();
                } else {
                    scrapped += disposition.quantity();
                }
                var responsibility = repository
                        .findResponsibilityByDisposition(disposition.dispositionFactBusinessId())
                        .orElse(null);
                dispositions.add(new DispositionView.DispositionTrace(
                        disposition.dispositionFactBusinessId(), disposition.type(),
                        disposition.physicalDestination(), disposition.quantity(),
                        disposition.terminalExitBusinessId(), disposition.repairSourceBusinessId(),
                        responsibility == null ? null : responsibility.responsibilityType(),
                        responsibility == null ? null : responsibility.responsibleSubjectBusinessId(),
                        responsibility == null ? null : responsibility.waivedReason()));
            }
            traces.add(new DispositionView.DefectTrace(
                    defect.defectFactBusinessId(), defect.defectReasonCode(), defect.defectReasonName(),
                    defect.quantity(), rework, scrapped, dispositions));
        }
        return traces;
    }

    /** 报废去向只允许销毁或转线下，转线下不能推断为免责任。 */
    static boolean requiresResponsibility(PhysicalDestination destination) {
        return destination == PhysicalDestination.DESTROYED
                || destination == PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY;
    }
}
