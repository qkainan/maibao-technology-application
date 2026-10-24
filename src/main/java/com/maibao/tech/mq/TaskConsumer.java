package com.maibao.tech.mq;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 消费者：手动 ack + 有限重试 + 失败落表。
 *
 * <p>重试不做「原地 sleep 再试」，而是带一个 {@code x-retry-count} 头把消息重新投回队列：
 * 这样重试次数跟着消息走，多实例部署时换个实例也能读到。次数用尽后写
 * {@code failed_task} 再丢弃，避免毒消息把队列堵死。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.mq", name = "enabled", havingValue = "true")
public class TaskConsumer {

    /** 重试次数放在消息头里，消息重新投递后仍然有效 */
    static final String RETRY_HEADER = "x-retry-count";

    private static final Logger log = LoggerFactory.getLogger(TaskConsumer.class);

    private final TaskProcessor processor;
    private final FailedTaskRepository failedTaskRepository;
    private final RabbitTemplate rabbitTemplate;
    private final MeterRegistry meterRegistry;
    private final String exchange;
    private final String routingKey;
    private final int maxRetries;

    public TaskConsumer(TaskProcessor processor,
                        FailedTaskRepository failedTaskRepository,
                        RabbitTemplate rabbitTemplate,
                        MeterRegistry meterRegistry,
                        @Value("${app.mq.exchange}") String exchange,
                        @Value("${app.mq.routing-key}") String routingKey,
                        @Value("${app.mq.max-retries:3}") int maxRetries) {
        this.processor = processor;
        this.failedTaskRepository = failedTaskRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.meterRegistry = meterRegistry;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.maxRetries = maxRetries;
    }

    @RabbitListener(queues = "${app.mq.queue}")
    public void onMessage(TaskMessage message, Message raw, Channel channel) throws IOException {
        long tag = raw.getMessageProperties().getDeliveryTag();
        int attempt = attemptOf(raw);
        try {
            processor.process(message);
            channel.basicAck(tag, false);
            meterRegistry.counter("mq.consume.success").increment();
        } catch (DataIntegrityViolationException duplicated) {
            // 唯一约束挡住了重复投递：这是幂等生效的正常路径，不是故障
            log.info("duplicate message, ack and skip: {}", message.taskId());
            channel.basicAck(tag, false);
            meterRegistry.counter("mq.consume.duplicate").increment();
        } catch (Exception e) {
            handleFailure(message, channel, tag, attempt, e);
        }
    }

    private void handleFailure(TaskMessage message, Channel channel, long tag,
                               int attempt, Exception e) throws IOException {
        if (attempt < maxRetries) {
            republish(message, attempt + 1);
            channel.basicAck(tag, false);
            meterRegistry.counter("mq.consume.retry").increment();
            log.warn("consume failed (attempt {}/{}), republished: {} - {}",
                    attempt + 1, maxRetries, message.taskId(), e.toString());
            return;
        }
        failedTaskRepository.save(new FailedTask(message.taskId(), message.payload(), e.toString(), attempt + 1));
        channel.basicNack(tag, false, false);
        meterRegistry.counter("mq.consume.failed").increment();
        log.error("consume failed after {} attempts, recorded to failed_task: {}",
                attempt + 1, message.taskId(), e);
    }

    private void republish(TaskMessage message, int attempt) {
        rabbitTemplate.convertAndSend(exchange, routingKey, message, m -> {
            m.getMessageProperties().setMessageId(message.taskId());
            m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            m.getMessageProperties().setHeader(RETRY_HEADER, attempt);
            return m;
        });
    }

    private int attemptOf(Message raw) {
        Object value = raw.getMessageProperties().getHeader(RETRY_HEADER);
        return value instanceof Number n ? n.intValue() : 0;
    }
}
