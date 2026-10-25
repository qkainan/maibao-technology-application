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
 * <p>发送前先用 nextRetryAt 做租约 CAS 抢占，抢到才发；发完等 confirm 回调标 SENT。
 * 即使 confirm 丢失，租约到期后对账/扫描会重新投递，而不是永远卡住。</p>
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository repository;
    private final OutboxService outboxService;
    private final RabbitTemplate rabbitTemplate;
    private final int maxAttempts;
    private final long consumeTimeoutSeconds;
    private final long leaseSeconds;

    public OutboxPublisher(OutboxRepository repository,
                           OutboxService outboxService,
                           RabbitTemplate rabbitTemplate,
                           @Value("${app.mq.outbox-max-attempts:5}") int maxAttempts,
                           @Value("${app.mq.consume-timeout-seconds:300}") long consumeTimeoutSeconds,
                           @Value("${app.mq.outbox-lease-seconds:30}") long leaseSeconds) {
        this.repository = repository;
        this.outboxService = outboxService;
        this.rabbitTemplate = rabbitTemplate;
        this.maxAttempts = maxAttempts;
        this.consumeTimeoutSeconds = consumeTimeoutSeconds;
        this.leaseSeconds = leaseSeconds;
    }

    @Scheduled(fixedDelayString = "${app.mq.outbox-scan-ms:2000}", initialDelay = 3000)
    public void publishPending() {
        List<OutboxMessage> batch = repository.findTop20ByStatusAndNextRetryAtLessThanEqualOrderByIdAsc(
                OutboxMessage.PENDING, Instant.now());
        for (OutboxMessage row : batch) {
            Instant now = Instant.now();
            // CAS 抢占：扫描可能有多个实例同时拿到同一行，但 UPDATE 只有一行能成功。
            // 抢到的实例把 next_retry_at 推到租约到期时间；没抢到的直接跳过。
            if (repository.claim(row.getId(), OutboxMessage.PENDING,
                    now.plusSeconds(leaseSeconds), now) != 1) {
                log.debug("outbox message {} already claimed by another instance", row.getId());
                continue;
            }
            try {
                TaskMessage message = outboxService.toMessage(row);
                CorrelationData correlation = new CorrelationData(String.valueOf(row.getId()));
                rabbitTemplate.convertAndSend(row.getExchangeName(), row.getRoutingKey(), message, m -> {
                    m.getMessageProperties().setMessageId(message.taskId());
                    m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return m;
                }, correlation);
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
