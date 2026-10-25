package com.maibao.tech.mq.outbox;

import com.maibao.tech.mq.outbox.repository.OutboxRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.maibao.tech.mq.outbox.domain.OutboxMessage;
import com.maibao.tech.mq.domain.TaskMessage;

/**
 * 业务侧写 outbox：只落库，不碰 broker。
 *
 * <p>消息体统一存 JSON，投递时按调用方给的 exchange / routingKey 发送；
 * 幂等键由调用方决定（成本用 request_id，任务用 taskId）。</p>
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;
    private final String taskExchange;
    private final String taskRoutingKey;

    public OutboxService(OutboxRepository repository,
                         ObjectMapper objectMapper,
                         @Value("${app.mq.exchange}") String taskExchange,
                         @Value("${app.mq.routing-key}") String taskRoutingKey) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.taskExchange = taskExchange;
        this.taskRoutingKey = taskRoutingKey;
    }

    /** 通用入队：幂等键重复时返回已有记录，不再产生第二条消息 */
    public OutboxMessage enqueue(String messageId, String exchange, String routingKey, Object payload) {
        try {
            return repository.save(new OutboxMessage(messageId, exchange, routingKey, toJson(payload)));
        } catch (DataIntegrityViolationException duplicated) {
            log.info("duplicate outbox message {}, reuse existing row", messageId);
            return repository.findByMessageId(messageId)
                    .orElseThrow(() -> duplicated);
        }
    }

    /** 任务消息的便捷入口（任务链路当前停用，保留给后续异步任务） */
    public OutboxMessage enqueue(TaskMessage message) {
        return enqueue(message.taskId(), taskExchange, taskRoutingKey, message);
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize outbox payload", e);
        }
    }
}
