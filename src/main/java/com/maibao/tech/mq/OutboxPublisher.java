package com.maibao.tech.mq;

import com.maibao.tech.mq.repository.OutboxRepository;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.maibao.tech.mq.domain.OutboxMessage;
import com.maibao.tech.mq.domain.TaskMessage;

/**
 * outbox 投递器：业务只负责落库，真正发消息在这里。
 *
 * <p>为什么不能"业务 commit 后直接 send"：send 失败就永久丢消息，且没有任何记录可补救。
 * 先落 outbox，再由这里投递，失败就退避重试，消息始终有据可查。</p>
 *
 * <p>发送成功后只把 nextRetryAt 往后推，等 confirm 回调再标 SENT —— 这样即使 confirm
 * 丢失，对账任务也能把它打回 PENDING 重投，而不是每 2 秒重复发送。</p>
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long CONFIRM_WAIT_SECONDS = 30;

    private final OutboxRepository repository;
    private final OutboxService outboxService;
    private final RabbitTemplate rabbitTemplate;
    private final int maxAttempts;
    private final long consumeTimeoutSeconds;

    public OutboxPublisher(OutboxRepository repository,
                           OutboxService outboxService,
                           RabbitTemplate rabbitTemplate,
                           @Value("${app.mq.outbox-max-attempts:5}") int maxAttempts,
                           @Value("${app.mq.consume-timeout-seconds:300}") long consumeTimeoutSeconds) {
        this.repository = repository;
        this.outboxService = outboxService;
        this.rabbitTemplate = rabbitTemplate;
        this.maxAttempts = maxAttempts;
        this.consumeTimeoutSeconds = consumeTimeoutSeconds;
    }

    @Scheduled(fixedDelayString = "${app.mq.outbox-scan-ms:2000}", initialDelay = 3000)
    public void publishPending() {
        List<OutboxMessage> batch = repository.findTop20ByStatusAndNextRetryAtLessThanEqualOrderByIdAsc(
                OutboxMessage.PENDING, Instant.now());
        for (OutboxMessage row : batch) {
            try {
                TaskMessage message = outboxService.toMessage(row);
                CorrelationData correlation = new CorrelationData(String.valueOf(row.getId()));
                rabbitTemplate.convertAndSend(row.getExchangeName(), row.getRoutingKey(), message, m -> {
                    m.getMessageProperties().setMessageId(message.taskId());
                    m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return m;
                }, correlation);
                repository.markPublished(row.getId(),
                        Instant.now().plusSeconds(CONFIRM_WAIT_SECONDS), Instant.now());
            } catch (Exception e) {
                scheduleRetry(row, e);
            }
        }
    }

    /** 对账：SENT 但没有消费回执的重新投递；重试次数用尽的标记失败 */
    @Scheduled(fixedDelayString = "${app.mq.outbox-reconcile-ms:60000}", initialDelay = 60000)
    public void reconcile() {
        Instant now = Instant.now();
        int requeued = repository.requeueStale(OutboxMessage.PENDING, OutboxMessage.SENT,
                now.minusSeconds(consumeTimeoutSeconds), now);
        int failed = repository.failExhausted(OutboxMessage.PENDING, OutboxMessage.FAILED, maxAttempts, now);
        if (requeued > 0 || failed > 0) {
            log.warn("outbox reconcile: requeued={} failed={}", requeued, failed);
        }
    }

    /** 发送失败：指数退避，上限 60s */
    public void scheduleRetry(OutboxMessage row, Exception e) {
        long delay = Math.min(60, 1L << Math.min(6, row.getAttempts()));
        repository.markRetry(row.getId(), OutboxMessage.PENDING,
                Instant.now().plusSeconds(delay), abbreviate(e.getMessage()), Instant.now());
        log.warn("outbox publish failed for message {} (attempt {}), retry in {}s: {}",
                row.getMessageId(), row.getAttempts() + 1, delay, e.toString());
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 255 ? text.substring(0, 255) : text;
    }
}
