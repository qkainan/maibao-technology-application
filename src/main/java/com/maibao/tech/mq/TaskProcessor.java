package com.maibao.tech.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务的幂等落库 + 业务动作。
 *
 * <p>两件事必须在同一个事务里：先写去重记录、再干活。如果业务抛异常，
 * 去重记录随事务一起回滚 —— 否则重试时会被误判成「重复消息」而直接跳过，
 * 任务就悄悄丢了。</p>
 *
 * <p>并发下同一 taskId 的两个消费者只有一个能提交，另一个会撞唯一约束，
 * 由调用方捕获 {@link org.springframework.dao.DataIntegrityViolationException}。</p>
 */
@Service
public class TaskProcessor {

    private static final Logger log = LoggerFactory.getLogger(TaskProcessor.class);

    private final ProcessedTaskRepository repository;

    public TaskProcessor(ProcessedTaskRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void process(TaskMessage message) {
        repository.save(new ProcessedTask(message.taskId()));
        // 真实业务动作的位置；本项目只记录一条日志
        log.info("[mq] handle task {} payload={}", message.taskId(), message.payload());
    }
}
