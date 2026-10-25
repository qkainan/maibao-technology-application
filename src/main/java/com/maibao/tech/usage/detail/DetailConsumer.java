package com.maibao.tech.usage.detail;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

import com.maibao.tech.mq.domain.FailedTask;
import com.maibao.tech.mq.repository.FailedTaskRepository;
import com.maibao.tech.usage.util.CostProperties;

import io.micrometer.core.instrument.MeterRegistry;
import com.maibao.tech.usage.domain.dto.DetailBatch;

/**
 * 明细落库消费者：手动 ack + 有限重投 + 终态进死信。
 *
 * <p>幂等不在这一层做 —— {@code user_query_log.request_id} 有唯一键，
 * 重投时 {@code INSERT IGNORE} 直接跳过，所以重试是安全的。</p>
 */
@Component
@ConditionalOnProperty(name = "app.cost.detail.mode", havingValue = "mq")
public class DetailConsumer {

    static final String RETRY_HEADER = "x-retry-count";

    private static final Logger log = LoggerFactory.getLogger(DetailConsumer.class);

    private final DetailWriter writer;
    private final RabbitTemplate rabbitTemplate;
    private final FailedTaskRepository failedTaskRepository;
    private final MeterRegistry meterRegistry;
    private final CostProperties properties;

    public DetailConsumer(DetailWriter writer,
                          RabbitTemplate rabbitTemplate,
                          FailedTaskRepository failedTaskRepository,
                          MeterRegistry meterRegistry,
                          CostProperties properties) {
        this.writer = writer;
        this.rabbitTemplate = rabbitTemplate;
        this.failedTaskRepository = failedTaskRepository;
        this.meterRegistry = meterRegistry;
        this.properties = properties;
    }

    @RabbitListener(queues = "${app.cost.detail.queue}")
    public void onMessage(DetailBatch batch, Message raw, Channel channel) throws IOException {
        long tag = raw.getMessageProperties().getDeliveryTag();
        int attempt = attemptOf(raw);
        try {
            int rows = writer.write(batch.rows());
            channel.basicAck(tag, false);
            meterRegistry.counter("mq.detail.consume.success").increment();
            log.debug("detail batch {} consumed: rows={} batchId={}", batch.batchId(), rows, batch.batchId());
        } catch (Exception e) {
            handleFailure(batch, channel, tag, attempt, e);
        }
    }

    private void handleFailure(DetailBatch batch, Channel channel, long tag,
                               int attempt, Exception e) throws IOException {
        if (attempt < properties.getDetail().getMaxRetries()
                && republishWithDelay(batch, attempt + 1)) {
            channel.basicAck(tag, false);
            meterRegistry.counter("mq.detail.consume.retry").increment();
            log.warn("detail consume failed (attempt {}/{}), republished: batchId={} - {}",
                    attempt + 1, properties.getDetail().getMaxRetries(), batch.batchId(), e.toString());
            return;
        }
        // 终态：先落库留痕，再 nack 进死信。顺序反了会留下"消息没了、记录也没有"的窗口
        failedTaskRepository.save(new FailedTask("detail:" + batch.batchId(),
                "rows=" + batch.rows().size(), e.toString(), attempt + 1));
        channel.basicNack(tag, false, false);
        meterRegistry.counter("mq.detail.consume.failed").increment();
        log.error("detail consume exhausted, batchId={} rows={} - {}",
                batch.batchId(), batch.rows().size(), e.toString());
    }

    /**
     * 投到退避队列（不是主队列）：消息在延迟队列里等到 TTL 过期，再由死信路由回主队列。
     *
     * <p>为什么要退避：消费失败多半是 DB 抖动或下游限流，立即重投三次会在毫秒内烧完，
     * 等于没重试。按 5s / 30s / 2min 递进，给下游恢复的时间。</p>
     *
     * <p>发送失败不能把异常抛出去：listener 抛异常时容器会 requeue，而 {@code x-retry-count}
     * 不会变，结果就是同一条消息无限重投。</p>
     */
    private boolean republishWithDelay(DetailBatch batch, int attempt) {
        CostProperties.Detail detail = properties.getDetail();
        long delayMs = detail.delayForAttempt(attempt);
        if (delayMs <= 0) {
            return false;
        }
        try {
            rabbitTemplate.convertAndSend(detail.getRetryExchange(),
                    detail.retryRoutingKey(delayMs), batch, message -> {
                        MessageProperties props = message.getMessageProperties();
                        props.setMessageId(batch.batchId());
                        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        props.setHeader(RETRY_HEADER, attempt);
                        return message;
                    });
            log.debug("detail retry scheduled: batchId={} attempt={} delay={}ms",
                    batch.batchId(), attempt, delayMs);
            return true;
        } catch (RuntimeException e) {
            log.error("detail retry publish failed, giving up: batchId={} - {}",
                    batch.batchId(), e.toString());
            return false;
        }
    }

    private int attemptOf(Message raw) {
        Object value = raw.getMessageProperties().getHeader(RETRY_HEADER);
        return value instanceof Number number ? number.intValue() : 0;
    }
}
