package com.yumi.platform.idempotency.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.idempotency.CommandExecution;
import com.yumi.platform.api.idempotency.CommandExecutionFacade;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.idempotency.CommandRequest;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** 幂等执行门面的平台实现：复用 PlatformCommandService 的事务与死锁重试。 */
@Component
public class CommandExecutionFacadeImpl implements CommandExecutionFacade {

    private final PlatformCommandService commands;
    private final ObjectMapper objectMapper;

    public CommandExecutionFacadeImpl(
            PlatformCommandService commands, ObjectMapper objectMapper) {
        this.commands = Objects.requireNonNull(commands, "命令服务不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "对象映射器不能为空");
    }

    @Override
    public CommandExecution execute(CommandRequest request, Supplier<CommandOutcome> body) {
        return commands.execute(request, body);
    }

    @Override
    public CommandExecution execute(
            String scopeKey,
            String idempotencyKey,
            Object requestBody,
            Supplier<CommandOutcome> body) {
        return commands.execute(
                new CommandRequest(
                        scopeKey, idempotencyKey,
                        PlatformCommandService.fingerprint(objectMapper, requestBody)),
                body);
    }
}
