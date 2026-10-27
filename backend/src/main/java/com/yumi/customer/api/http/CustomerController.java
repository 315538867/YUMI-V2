package com.yumi.customer.api.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.customer.api.ChangeCustomerStatusCommand;
import com.yumi.customer.api.CreateCustomerCommand;
import com.yumi.customer.api.CustomerApplicationService;
import com.yumi.customer.api.CustomerQuery;
import com.yumi.customer.api.CustomerRef;
import com.yumi.customer.api.CustomerStatus;
import com.yumi.customer.api.CustomerView;
import com.yumi.customer.api.UpdateCustomerCommand;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.idempotency.CommandRequest;
import com.yumi.platform.api.http.RequestContextResolver;
import com.yumi.platform.api.idempotency.CommandExecutionFacade;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户 HTTP 适配器（交付清单 C01）。
 *
 * <p>只调用客户公开命令与查询；创建、修改与启停均按平台幂等契约执行，查询不需要幂等键。
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerApplicationService customers;

    private final CustomerQuery customerQuery;

    private final CommandExecutionFacade commands;

    private final ObjectMapper objectMapper;

    private final RequestContextResolver contextResolver;

    public CustomerController(
            CustomerApplicationService customers,
            CustomerQuery customerQuery,
            CommandExecutionFacade commands,
            ObjectMapper objectMapper,
            RequestContextResolver contextResolver) {
        this.customers = customers;
        this.customerQuery = customerQuery;
        this.commands = commands;
        this.objectMapper = objectMapper;
        this.contextResolver = contextResolver;
    }

    /** 修改客户请求体；客户标识来自路径。 */
    public record UpdateCustomerRequest(
            String name, String contactName, String phone, String defaultAddress,
            long expectedRowVersion) {
    }

    /** 客户启停请求体；客户标识来自路径。 */
    public record ChangeStatusRequest(CustomerStatus targetStatus, long expectedRowVersion) {
    }

    @PostMapping
    public ResponseEntity<CustomerView> create(
            @RequestBody CreateCustomerCommand request, HttpServletRequest servletRequest) {
        var context = contextResolver.resolveCommand(servletRequest);
        var execution = commands.execute(
                new CommandRequest(
                        "customer.create",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, request)),
                () -> {
                    CustomerRef created = customers.create(request);
                    return new CommandOutcome("CUSTOMER", created.customerBusinessId());
                });
        CustomerView view = customerQuery.getCurrent(execution.outcome().resultBusinessId());
        return ResponseEntity.status(execution.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(view);
    }

    @PutMapping("/{customerBusinessId}")
    public CustomerView update(
            @PathVariable String customerBusinessId,
            @RequestBody UpdateCustomerRequest request,
            HttpServletRequest servletRequest) {
        UpdateCustomerCommand command = new UpdateCustomerCommand(
                customerBusinessId, request.name(), request.contactName(), request.phone(),
                request.defaultAddress(), request.expectedRowVersion());
        var context = contextResolver.resolveCommand(servletRequest);
        commands.execute(
                new CommandRequest(
                        "customer.update",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, command)),
                () -> {
                    CustomerRef updated = customers.update(command);
                    return new CommandOutcome("CUSTOMER", updated.customerBusinessId());
                });
        return customerQuery.getCurrent(customerBusinessId);
    }

    @PostMapping("/{customerBusinessId}/status")
    public CustomerView changeStatus(
            @PathVariable String customerBusinessId,
            @RequestBody ChangeStatusRequest request,
            HttpServletRequest servletRequest) {
        ChangeCustomerStatusCommand command = new ChangeCustomerStatusCommand(
                customerBusinessId, request.targetStatus(), request.expectedRowVersion());
        var context = contextResolver.resolveCommand(servletRequest);
        commands.execute(
                new CommandRequest(
                        "customer.changeStatus",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, command)),
                () -> {
                    CustomerRef updated = customers.changeStatus(command);
                    return new CommandOutcome("CUSTOMER", updated.customerBusinessId());
                });
        return customerQuery.getCurrent(customerBusinessId);
    }

    @GetMapping("/active-count")
    public long activeCount() {
        return customerQuery.countActive();
    }

    @GetMapping
    public List<CustomerView> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) CustomerStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return customerQuery.search(keyword, status, page, size);
    }

    @GetMapping("/{customerBusinessId}")
    public CustomerView get(@PathVariable String customerBusinessId) {
        return customerQuery.getCurrent(customerBusinessId);
    }

    @GetMapping("/{customerBusinessId}/history")
    public java.util.List<com.yumi.customer.api.CustomerChangeView> history(
            @PathVariable String customerBusinessId) {
        return customerQuery.history(customerBusinessId);
    }
}
