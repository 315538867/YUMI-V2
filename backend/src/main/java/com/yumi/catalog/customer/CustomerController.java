package com.yumi.catalog.customer;

import com.yumi.catalog.customer.CustomerDtos.CreateCustomerRequest;
import com.yumi.catalog.customer.CustomerDtos.CustomerDetail;
import com.yumi.catalog.customer.CustomerDtos.CustomerPatchRequest;
import com.yumi.catalog.customer.CustomerDtos.CustomerView;
import com.yumi.catalog.customer.CustomerDtos.DuplicatePrompt;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 客户 API（任务 2.5/2.6）：无 DELETE；重复只提示；详情附只读 summary（由订单与收退款事实实时聚合）。
 * 成功响应由 EnvelopeAdvice 统一包 {code,message,fieldErrors,requestId,data}。
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateCustomerRequest request) {
        var outcome = customerService.create(request);
        if (outcome.created()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(outcome.customer());
        }
        return ResponseEntity.ok(new DuplicatePrompt(false, outcome.duplicateCandidates()));
    }

    @GetMapping
    public List<CustomerView> list(@RequestParam(value = "name", required = false) String name,
                                    @RequestParam(value = "phone", required = false) String phone) {
        return customerService.list(name, phone);
    }

    @GetMapping("/{id}")
    public CustomerDetail detail(@PathVariable("id") long id) {
        return customerService.detail(id);
    }

    @PatchMapping("/{id}")
    public CustomerView patch(@PathVariable("id") long id,
                              @Valid @RequestBody CustomerPatchRequest request) {
        return customerService.patch(id, request);
    }
}
