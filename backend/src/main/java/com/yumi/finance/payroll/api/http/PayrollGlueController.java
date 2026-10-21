package com.yumi.finance.payroll.api.http;

import com.yumi.finance.payroll.api.GlueAllowancePolicyRef;
import com.yumi.finance.payroll.api.GlueAllowancePolicyView;
import com.yumi.finance.payroll.api.GlueMonthReferenceView;
import com.yumi.finance.payroll.api.PayrollGlueAllowancePolicyService;
import com.yumi.finance.payroll.api.PayrollGlueAllowanceQuery;
import com.yumi.finance.payroll.api.PublishGlueAllowancePolicyCommand;
import com.yumi.finance.payroll.api.RunGlueMonthReferenceCommand;
import com.yumi.platform.api.http.HttpHeaders;
import com.yumi.platform.api.value.WeightGram;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 胶水自然月设置 HTTP 适配器（交付清单 F02）：只发布设置版本并运行或读取员工自然月减免参考，
 * 不按工资期间拆分，也不改写已核定工资。
 */
@RestController
@RequestMapping("/api/payroll/glue")
public class PayrollGlueController {

    private final PayrollGlueAllowancePolicyService policies;

    private final PayrollGlueAllowanceQuery queries;

    public PayrollGlueController(
            PayrollGlueAllowancePolicyService policies, PayrollGlueAllowanceQuery queries) {
        this.policies = policies;
        this.queries = queries;
    }

    /** 发布胶水设置请求体；同一生效月追加版本。 */
    public record PublishPolicyRequest(
            LocalDate effectiveMonth,
            WeightGram exemptWeightGrams,
            String reasonText,
            long expectedGuardVersion) {
    }

    /** 运行员工自然月胶水参考请求体。 */
    public record RunMonthReferenceRequest(String employeeBusinessId, LocalDate monthStart) {
    }

    @PostMapping("/policies")
    public ResponseEntity<GlueAllowancePolicyRef> publishPolicy(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody PublishPolicyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(policies.publish(
                new PublishGlueAllowancePolicyCommand(
                        idempotencyKey, request.effectiveMonth(),
                        request.exemptWeightGrams(), request.reasonText(),
                        request.expectedGuardVersion())));
    }

    @GetMapping("/policies")
    public GlueAllowancePolicyView getPolicy(
            @RequestParam(name = "month", required = false) String month,
            @RequestParam(name = "monthStart", required = false) String monthStart) {
        return queries.getPolicy(requireMonth(month, monthStart));
    }

    @PostMapping("/month-references")
    public ResponseEntity<GlueMonthReferenceView> runMonthReference(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody RunMonthReferenceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(queries.runMonthReference(
                new RunGlueMonthReferenceCommand(
                        idempotencyKey, request.employeeBusinessId(), request.monthStart())));
    }

    @GetMapping("/month-references/{month}")
    public GlueMonthReferenceView getMonthReference(
            @PathVariable String month,
            @RequestParam String employeeBusinessId) {
        return queries.getMonthReference(employeeBusinessId, parseMonth(month));
    }

    @GetMapping("/month-references")
    public GlueMonthReferenceView getMonthReferenceByQuery(
            @RequestParam String employeeBusinessId,
            @RequestParam(name = "month", required = false) String month,
            @RequestParam(name = "monthStart", required = false) String monthStart) {
        return queries.getMonthReference(
                employeeBusinessId, requireMonth(month, monthStart));
    }

    private static LocalDate requireMonth(String month, String monthStart) {
        String resolved = month != null && !month.isBlank() ? month : monthStart;
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalArgumentException("必须指定自然月");
        }
        return parseMonth(resolved);
    }

    private static LocalDate parseMonth(String value) {
        return value.length() == 7
                ? java.time.YearMonth.parse(value).atDay(1)
                : LocalDate.parse(value).withDayOfMonth(1);
    }
}
