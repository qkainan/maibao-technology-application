package com.maibao.tech.usage;

import java.io.IOException;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

import com.maibao.tech.mq.repository.FailedTaskRepository;
import com.maibao.tech.mq.repository.OutboxRepository;
import com.maibao.tech.mq.domain.FailedTask;
import com.maibao.tech.mq.domain.OutboxMessage;
import com.maibao.tech.usage.domain.CostMessage;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 成本事件消费者：手动 ack + 有限重试 + 终态落表/死信。
 *
 * <p>去重靠 {@code cost_message_log} 的唯一键；重复投递会被
 * {@link DataIntegrityViolationException} 拦下，属正常路径，ack 跳过。</p>
 */
@Component
public class CostConsumer {

    static final String RETRY_HEADER = "x-retry-count";

    private static final Logger log = LoggerFactory.getLogger(CostConsumer.class);

    private final CostProcessor processor;
    private final FailedTaskRepository failedTaskRepository;
    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final MeterRegistry meterRegistry;
    private final String exchange;
    private final String routingKey;
    private final int maxRetries;

    public CostConsumer(CostProcessor processor,
                        FailedTaskRepository failedTaskRepository,
                        OutboxRepository outboxRepository,
                        RabbitTemplate rabbitTemplate,
                        MeterRegistry meterRegistry,
                        @Value("${app.cost.exchange}") String exchange,
                        @Value("${app.cost.routing-key}") String routingKey,
                        @Value("${app.cost.max-retries:3}") int maxRetries) {
        this.processor = processor;
        this.failedTaskRepository = failedTaskRepository;
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.meterRegistry = meterRegistry;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.maxRetries = maxRetries;
    }

    @RabbitListener(queues = "${app.cost.queue}")
    public void onMessage(CostMessage message, Message raw, Channel channel) throws IOException {
        long tag = raw.getMessageProperties().getDeliveryTag();
        int attempt = attemptOf(raw);
        try {
            processor.process(message);
            channel.basicAck(tag, false);
            markConsumed(message);
            meterRegistry.counter("mq.cost.consume.success").increment();
        } catch (DataIntegrityViolationException duplicated) {
            log.info("duplicate cost message, ack and skip: {}", message.bizId());
            channel.basicAck(tag, false);
            markConsumed(message);
            meterRegistry.counter("mq.cost.consume.duplicate").increment();
        } catch (Exception e) {
            handleFailure(message, raw, channel, tag, attempt, e);
        }
    }

    private void handleFailure(CostMessage message, Message raw, Channel channel, long tag,
                               int attempt, Exception e) throws IOException {
        if (attempt < maxRetries) {
            republish(raw, attempt + 1);
            channel.basicAck(tag, false);
            meterRegistry.counter("mq.cost.consume.retry").increment();
            log.warn("cost consume failed (attempt {}/{}), republished: {} - {}",
                    attempt + 1, maxRetries, message.bizId(), e.toString());
            return;
        }
        failedTaskRepository.save(new FailedTask(message.bizId(), abbreviate(message.toString()),
                abbreviate(e.toString()), attempt + 1));
        // 终态同时终结 outbox，否则 SENT 行会被对账反复重投
        markOutboxFailed(message, e);
        channel.basicNack(tag, false, false);
        meterRegistry.counter("mq.cost.consume.failed").increment();
        log.error("cost consume failed after {} attempts, recorded to failed_task: {}",
                attempt + 1, message.bizId(), e);
    }

    private void republish(Message raw, int attempt) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("UTF-8");
        properties.setMessageId(raw.getMessageProperties().getMessageId());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader(RETRY_HEADER, attempt);
        rabbitTemplate.send(exchange, routingKey, new Message(raw.getBody(), properties));
    }

    private void markConsumed(CostMessage message) {
        try {
            outboxRepository.markConsumed(message.bizId(), OutboxMessage.CONSUMED,
                    OutboxMessage.SENT, OutboxMessage.PENDING, Instant.now());
        } catch (RuntimeException e) {
            log.warn("failed to mark cost outbox consumed for {}: {}", message.bizId(), e.toString());
        }
    }

    private void markOutboxFailed(CostMessage message, Exception cause) {
        try {
            outboxRepository.markFailedByMessageId(message.bizId(), OutboxMessage.FAILED,
                    abbreviate("cost consume failed: " + cause), Instant.now());
        } catch (RuntimeException e) {
            log.warn("failed to mark cost outbox failed for {}: {}", message.bizId(), e.toString());
        }
    }

    private int attemptOf(Message raw) {
        Object value = raw.getMessageProperties().getHeader(RETRY_HEADER);
        return value instanceof Number n ? n.intValue() : 0;
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 512 ? text.substring(0, 512) : text;
    }
}
