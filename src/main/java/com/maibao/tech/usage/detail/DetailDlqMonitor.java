package com.maibao.tech.usage.detail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.maibao.tech.usage.util.CostProperties;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 死信队列巡检：深度 &gt; 0 就写一条 ERROR。
 *
 * <p>为什么不给 DLQ 加消费者：消费掉就毁了现场 —— 死信需要人看、需要能重放。
 * 所以这里只"看"不"拿"。</p>
 *
 * <p>写 ERROR 会同时落进 {@code logs/error.YYYY-MM-DD.log}（见 logback-spring.xml），
 * 相当于"没人盯管理台也不会漏掉"。</p>
 */
@Component
public class DetailDlqMonitor {

    private static final Logger log = LoggerFactory.getLogger(DetailDlqMonitor.class);

    private final AmqpAdmin amqpAdmin;
    private final CostProperties properties;
    private final MeterRegistry meterRegistry;

    public DetailDlqMonitor(AmqpAdmin amqpAdmin, CostProperties properties, MeterRegistry meterRegistry) {
        this.amqpAdmin = amqpAdmin;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${app.cost.detail.dlq-check-ms:60000}", initialDelay = 45000)
    public void checkDepth() {
        String queue = properties.getDetail().getDlqQueue();
        QueueInformation info;
        try {
            info = amqpAdmin.getQueueInfo(queue);
        } catch (RuntimeException e) {
            log.warn("detail dlq depth check failed, queue={}: {}", queue, e.toString());
            return;
        }
        if (info == null) {
            return;
        }
        int depth = info.getMessageCount();
        meterRegistry.gauge("mq.detail.dlq.depth", depth);
        if (depth > 0) {
            log.error("detail dlq not empty: queue={} depth={} —— 需要人工查看或重放", queue, depth);
        }
    }
}
