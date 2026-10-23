package com.maibao.tech.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MQ 关闭时的降级实现：在调用线程同步执行。
 *
 * <p>与异步链路共用同一套幂等约束（processed_task），区别只在于省掉投递与确认环节；
 * app.mq.enabled=true 时由 RabbitTaskDispatcher 接管。</p>
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
