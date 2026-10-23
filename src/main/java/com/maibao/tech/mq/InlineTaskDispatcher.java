package com.maibao.tech.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MQ 关闭时的兜底实现：同步执行。
 *
 * <p>这样项目不装 RabbitMQ 也能启动、也能演示接口，
 * 打开 app.mq.enabled=true 后自动切成 RabbitMQ 实现。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.mq", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InlineTaskDispatcher implements TaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InlineTaskDispatcher.class);

    private final ProcessedTaskRepository repository;

    public InlineTaskDispatcher(ProcessedTaskRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean dispatch(TaskMessage message) {
        if (repository.existsByTaskId(message.taskId())) {
            log.info("duplicated task ignored: {}", message.taskId());
            return false;
        }
        repository.save(new ProcessedTask(message.taskId()));
        log.info("[inline] executed task {} payload={}", message.taskId(), message.payload());
        return false;
    }
}
