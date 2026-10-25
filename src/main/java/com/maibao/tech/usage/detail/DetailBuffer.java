package com.maibao.tech.usage.detail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.maibao.tech.usage.domain.dto.DetailBatch;
import com.maibao.tech.usage.domain.dto.DetailRecord;
import com.maibao.tech.usage.util.CostProperties;

/**
 * 明细的攒批缓冲。
 *
 * <p>请求线程只做一次 {@code offer}，落库交给后台：</p>
 * <ul>
 *   <li>{@code mode=mq}：每 200ms 把攒的一批封成一条消息发出去，消费端批量写库；</li>
 *   <li>{@code mode=local}：直接批量写库，不依赖 broker。</li>
 * </ul>
 *
 * <p>发布失败的处理顺序（"优先重试，兜底落日志"）：</p>
 * <ol>
 *   <li>发送抛异常 → 立即回落本地直写；</li>
 *   <li>broker 回 nack（没落盘）→ 把这一批<b>重新入本地队列</b>，下轮再发，最多 {@code publish-retries} 次；</li>
 *   <li>重试用尽 / 消息不可路由 → <b>ERROR 日志</b>（含完整 JSON），进 {@code logs/error.YYYY-MM-DD.log}。</li>
 * </ol>
 */
@Component
public class DetailBuffer {

    private static final Logger log = LoggerFactory.getLogger(DetailBuffer.class);

    /** CorrelationData 前缀：用来和 outbox（纯数字 id）区分 */
    public static final String CORRELATION_PREFIX = "detail:";

    private final BlockingQueue<DetailRecord> queue;
    private final int batchSize;
    private final DetailWriter writer;
    private final RabbitTemplate rabbitTemplate;
    private final CostProperties properties;
    private final ObjectMapper objectMapper;
    /** 已发出、等 confirm 的批次：batchId → 批次 + 已重试次数 */
    private final Map<String, Inflight> inflight = new ConcurrentHashMap<>();

    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    /** 发送抛异常、回落本地直写的次数 */
    private final AtomicLong fallbacks = new AtomicLong();
    /** confirm nack 后重新入队的次数 */
    private final AtomicLong publishRetries = new AtomicLong();

    public DetailBuffer(DetailWriter writer, RabbitTemplate rabbitTemplate,
                        CostProperties properties, ObjectMapper objectMapper) {
        this.writer = writer;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.batchSize = properties.getDetail().getBatchSize();
        this.queue = new ArrayBlockingQueue<>(properties.getDetail().getQueueCapacity());
    }

    /** 只入队，不做任何 IO。 */
    public void offer(DetailRecord row) {
        if (queue.offer(row)) {
            accepted.incrementAndGet();
            return;
        }
        long total = dropped.incrementAndGet();
        if (total == 1 || total % 1000 == 0) {
            log.warn("detail buffer full, dropped={} (cost already counted in redis)", total);
        }
    }

    @Scheduled(fixedDelayString = "${app.cost.detail.flush-ms:200}")
    public void flush() {
        while (true) {
            List<DetailRecord> batch = new ArrayList<>(batchSize);
            queue.drainTo(batch, batchSize);
            if (batch.isEmpty()) {
                return;
            }
            dispatch(batch);
        }
    }

    @PreDestroy
    public void shutdown() {
        flush();
        log.info("detail buffer closed: accepted={} dropped={} fallbacks={} publishRetries={} pending={} inflight={}",
                accepted.get(), dropped.get(), fallbacks.get(), publishRetries.get(),
                queue.size(), inflight.size());
        if (!inflight.isEmpty()) {
            log.warn("detail buffer closed with {} unconfirmed batches (可能丢，靠 broker 侧确认后仍不落地的情况极少)",
                    inflight.size());
        }
    }

    public int pending() {
        return queue.size();
    }

    public long accepted() {
        return accepted.get();
    }

    public long dropped() {
        return dropped.get();
    }

    public long fallbacks() {
        return fallbacks.get();
    }

    public long publishRetries() {
        return publishRetries.get();
    }

    /**
     * confirm 回调转成事件后在这里处理。
     *
     * <p>ack：从 inflight 里删掉；nack：还有重试预算就重新入队，没有就写 ERROR 日志兜底。</p>
     */
    @EventListener
    public void onPublishResult(DetailPublishResult result) {
        Inflight state = inflight.get(result.batchId());
        if (state == null) {
            return;
        }
        if (result.ack()) {
            inflight.remove(result.batchId());
            return;
        }
        int used = state.attempts() + 1;
        if (used < properties.getDetail().getPublishRetries()) {
            inflight.put(result.batchId(), new Inflight(state.batch(), used));
            // 重新入本地队列：下一轮 flush 会再发一次（同一条消息，可能换成新的 batchId）
            state.batch().rows().forEach(this::offer);
            publishRetries.incrementAndGet();
            log.warn("detail publish not confirmed, re-queued: batchId={} attempt={}/{} cause={}",
                    result.batchId(), used, properties.getDetail().getPublishRetries(), result.cause());
            return;
        }
        inflight.remove(result.batchId());
        log.error("detail publish failed after {} attempts, batchId={} rows={} cause={} payload={}",
                used, result.batchId(), state.batch().rows().size(), result.cause(),
                abbreviate(toJson(state.batch())));
    }

    // ------------------------------------------------------------------

    private void dispatch(List<DetailRecord> batch) {
        if (properties.getDetail().isViaMq() && publish(batch)) {
            return;
        }
        writeDirectly(batch);
    }

    private boolean publish(List<DetailRecord> batch) {
        DetailBatch message = DetailBatch.of(batch);
        CorrelationData correlation = new CorrelationData(CORRELATION_PREFIX + message.batchId());
        if (inflight.size() < 10_000) {
            inflight.put(message.batchId(), new Inflight(message, 0));
        }
        try {
            rabbitTemplate.convertAndSend(properties.getDetail().getExchange(),
                    properties.getDetail().getRoutingKey(), message, raw -> {
                        raw.getMessageProperties().setMessageId(message.batchId());
                        raw.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        return raw;
                    }, correlation);
            return true;
        } catch (RuntimeException e) {
            inflight.remove(message.batchId());
            long total = fallbacks.incrementAndGet();
            if (total == 1 || total % 50 == 0) {
                log.warn("detail publish threw, falling back to direct write (times={}): {}",
                        total, e.toString());
            }
            return false;
        }
    }

    private void writeDirectly(List<DetailRecord> batch) {
        try {
            writer.write(batch);
        } catch (RuntimeException e) {
            log.error("detail batch insert failed, size={} payload={}: {}",
                    batch.size(), abbreviate(toJson(batch)), e.toString());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "<unserializable>";
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        int max = 4000;
        return text.length() <= max ? text : text.substring(0, max) + "...<truncated>";
    }

    /** 一批消息的在途状态 */
    private record Inflight(DetailBatch batch, int attempts) {
    }
}
