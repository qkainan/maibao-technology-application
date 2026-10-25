package com.maibao.tech.mq;

import com.maibao.tech.mq.repository.OutboxRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.maibao.tech.mq.domain.OutboxMessage;
import com.maibao.tech.mq.domain.TaskMessage;

/**
 * 业务侧写 outbox：只落库，不碰 broker。
 *
 * <p>同一个 taskId 重复提交会撞唯一键 —— 这正是业务幂等：第二次不产生新消息，
 * 返回已有的那一条。</p>
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;
    private final String exchange;
    private final String routingKey;

    public OutboxService(OutboxRepository repository,
                         ObjectMapper objectMapper,
                         @Value("${app.mq.exchange}") String exchange,
                         @Value("${app.mq.routing-key}") String routingKey) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    public OutboxMessage enqueue(TaskMessage message) {
        try {
            return repository.save(new OutboxMessage(message.taskId(), exchange, routingKey, toJson(message)));
        } catch (DataIntegrityViolationException duplicated) {
            log.info("duplicate submit for task {}, reuse existing outbox row", message.taskId());
            return repository.findByMessageId(message.taskId())
                    .orElseThrow(() -> duplicated);
        }
    }

    public TaskMessage toMessage(OutboxMessage row) throws JsonProcessingException {
        return objectMapper.readValue(row.getPayload(), TaskMessage.class);
    }

    private String toJson(TaskMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize task message", e);
        }
    }
}
