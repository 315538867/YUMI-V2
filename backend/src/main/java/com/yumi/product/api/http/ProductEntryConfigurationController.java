package com.yumi.product.api.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.idempotency.CommandRequest;
import com.yumi.platform.api.http.RequestContextResolver;
import com.yumi.platform.api.idempotency.CommandExecutionFacade;
import com.yumi.product.api.ProductEntryConfigurationService;
import com.yumi.product.api.ProductEntryConfigurationView;
import com.yumi.product.api.SaveMakingStarOptionCommand;
import com.yumi.product.api.SaveProcessTimeOptionCommand;
import com.yumi.product.api.SaveProductEntryDefaultsCommand;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品录入配置 HTTP 适配器（交付清单 D02）。
 *
 * <p>只维护录入默认值、制作星级与工序标准时间选项；写入按平台幂等契约执行，不改既有商品。
 */
@RestController
@RequestMapping("/api/product-entry-configuration")
public class ProductEntryConfigurationController {

    private final ProductEntryConfigurationService configuration;

    private final CommandExecutionFacade commands;

    private final ObjectMapper objectMapper;

    private final RequestContextResolver contextResolver;

    public ProductEntryConfigurationController(
            ProductEntryConfigurationService configuration,
            CommandExecutionFacade commands,
            ObjectMapper objectMapper,
            RequestContextResolver contextResolver) {
        this.configuration = configuration;
        this.commands = commands;
        this.objectMapper = objectMapper;
        this.contextResolver = contextResolver;
    }

    /** 制作星级请求体；星级代码来自路径。 */
    public record MakingStarRequest(
            String name, int standardMinutes, int sortOrder, boolean active,
            long expectedRowVersion) {
    }

    /** 工序标准时间选项请求体；选项标识来自路径。 */
    public record ProcessTimeOptionRequest(
            String processCode,
            String optionCode,
            String name,
            int standardMinutes,
            int sortOrder,
            boolean active,
            long expectedRowVersion) {
    }

    @GetMapping
    public ProductEntryConfigurationView getConfiguration() {
        return configuration.getConfiguration();
    }

    @PutMapping("/defaults")
    public ResponseEntity<ProductEntryConfigurationView> saveDefaults(
            @RequestBody SaveProductEntryDefaultsCommand request,
            HttpServletRequest servletRequest) {
        var context = contextResolver.resolveCommand(servletRequest);
        commands.execute(
                new CommandRequest(
                        "product.entryConfiguration.saveDefaults",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, request)),
                () -> {
                    configuration.saveDefaults(request);
                    return new CommandOutcome("PRODUCT_ENTRY_DEFAULTS", "DEFAULT");
                });
        return ResponseEntity.ok(configuration.getConfiguration());
    }

    @PutMapping("/making-stars/{starCode}")
    public ResponseEntity<ProductEntryConfigurationView> saveMakingStar(
            @PathVariable String starCode,
            @RequestBody MakingStarRequest request,
            HttpServletRequest servletRequest) {
        SaveMakingStarOptionCommand command = new SaveMakingStarOptionCommand(
                starCode, request.name(), request.standardMinutes(), request.sortOrder(),
                request.active(), request.expectedRowVersion());
        var context = contextResolver.resolveCommand(servletRequest);
        commands.execute(
                new CommandRequest(
                        "product.entryConfiguration.saveMakingStar",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, command)),
                () -> {
                    configuration.saveMakingStar(command);
                    return new CommandOutcome("MAKING_STAR", starCode);
                });
        return ResponseEntity.ok(configuration.getConfiguration());
    }

    @PutMapping("/process-time-options/{optionBusinessId}")
    public ResponseEntity<ProductEntryConfigurationView> saveProcessTimeOption(
            @PathVariable String optionBusinessId,
            @RequestBody ProcessTimeOptionRequest request,
            HttpServletRequest servletRequest) {
        SaveProcessTimeOptionCommand command = new SaveProcessTimeOptionCommand(
                optionBusinessId,
                request.processCode(),
                request.optionCode(),
                request.name(),
                request.standardMinutes(),
                request.sortOrder(),
                request.active(),
                request.expectedRowVersion());
        var context = contextResolver.resolveCommand(servletRequest);
        commands.execute(
                new CommandRequest(
                        "product.entryConfiguration.saveProcessTimeOption",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, command)),
                () -> {
                    configuration.saveProcessTimeOption(command);
                    return new CommandOutcome("PROCESS_TIME_OPTION", optionBusinessId);
                });
        return ResponseEntity.ok(configuration.getConfiguration());
    }
}
