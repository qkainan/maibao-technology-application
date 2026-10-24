package com.maibao.tech.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * MQ 关闭时的降级实现：在调用线程同步执行。
 *
 * <p>与异步链路共用同一个 {@link TaskProcessor}，所以幂等语义完全一致，
 * 区别只在于省掉了投递、确认与重试环节。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.mq", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InlineTaskDispatcher implements TaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InlineTaskDispatcher.class);

    private final TaskProcessor processor;

    public InlineTaskDispatcher(TaskProcessor processor) {
        this.processor = processor;
    }

    @Override
    public boolean dispatch(TaskMessage message) {
        try {
            processor.process(message);
        } catch (DataIntegrityViolationException duplicated) {
            log.info("duplicated task ignored: {}", message.taskId());
        }
        return false;
    }
}
