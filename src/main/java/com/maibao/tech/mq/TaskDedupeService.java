package com.maibao.tech.mq;

import com.maibao.tech.mq.repository.ProcessedTaskRepository;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.maibao.tech.mq.domain.ProcessedTask;

/**
 * 幂等锚点的独立事务。
 *
 * <p>业务动作要调外部模型，不能在同一个数据库事务里等——那会把连接一直占着。
 * 所以拆成两个短事务：先提交锚点（挡住重复投递），业务失败再撤销锚点。</p>
 */
@Service
public class TaskDedupeService {

    private final ProcessedTaskRepository repository;
    private final EntityManager entityManager;

    public TaskDedupeService(ProcessedTaskRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    /** 提交幂等锚点；重复投递会撞唯一约束，抛 DataIntegrityViolationException */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessed(String taskId) {
        repository.saveAndFlush(new ProcessedTask(taskId));
    }

    /** 业务失败时撤销锚点，否则重试会被误判成重复消息 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void unmark(String taskId) {
        entityManager.createQuery("delete from ProcessedTask p where p.taskId = :taskId")
                .setParameter("taskId", taskId)
                .executeUpdate();
    }
}
