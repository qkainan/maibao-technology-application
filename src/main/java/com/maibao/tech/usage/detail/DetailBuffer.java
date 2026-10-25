package com.maibao.tech.usage.detail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.maibao.tech.usage.util.CostProperties;
import com.maibao.tech.usage.domain.dto.DetailBatch;
import com.maibao.tech.usage.domain.dto.DetailRecord;

/**
 * 明细的攒批缓冲。
 *
 * <p>请求线程只做一次 {@code offer}，落库交给后台：</p>
 * <ul>
 *   <li>{@code app.cost.detail.mode=mq}：每 200ms 把攒的一批封成一条消息发出去，
 *       消费端批量写库 —— 进程被杀也不丢（消息已持久化）；</li>
 *   <li>{@code mode=local}：直接批量写库，不依赖 broker。</li>
 * </ul>
 *
 * <p><b>MQ 发送失败时立即回落本地直写</b>，所以 broker 抖动只影响延迟，不影响数据。</p>
 *
 * <p>队列满时丢弃并计数，绝不阻塞对话线程 —— 成本已经在 Redis 里累加了，
 * 丢掉的是"这一条的可追溯性"。</p>
 */
@Component
public class DetailBuffer {

    private static final Logger log = LoggerFactory.getLogger(DetailBuffer.class);

    private final BlockingQueue<DetailRecord> queue;
    private final int batchSize;
    private final DetailWriter writer;
    private final RabbitTemplate rabbitTemplate;
    private final CostProperties properties;

    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    /** MQ 发送失败、回落本地直写的次数 */
    private final AtomicLong fallbacks = new AtomicLong();

    public DetailBuffer(DetailWriter writer, RabbitTemplate rabbitTemplate, CostProperties properties) {
        this.writer = writer;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
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
        log.info("detail buffer closed: accepted={} dropped={} fallbacks={} pending={}",
                accepted.get(), dropped.get(), fallbacks.get(), queue.size());
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

    private void dispatch(List<DetailRecord> batch) {
        if (properties.getDetail().isViaMq() && publish(batch)) {
            return;
        }
        writeDirectly(batch);
    }

    private boolean publish(List<DetailRecord> batch) {
        try {
            rabbitTemplate.convertAndSend(properties.getDetail().getExchange(),
                    properties.getDetail().getRoutingKey(), DetailBatch.of(batch));
            return true;
        } catch (RuntimeException e) {
            long total = fallbacks.incrementAndGet();
            if (total == 1 || total % 50 == 0) {
                log.warn("detail publish failed, falling back to direct write (times={}): {}",
                        total, e.toString());
            }
            return false;
        }
    }

    private void writeDirectly(List<DetailRecord> batch) {
        try {
            writer.write(batch);
        } catch (RuntimeException e) {
            log.error("detail batch insert failed, size={}: {}", batch.size(), e.toString());
        }
    }
}
