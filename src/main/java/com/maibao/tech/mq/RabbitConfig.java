package com.maibao.tech.mq;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.maibao.tech.mq.outbox.domain.OutboxMessage;
import com.maibao.tech.mq.outbox.repository.OutboxRepository;
import com.maibao.tech.usage.detail.DetailBuffer;
import com.maibao.tech.usage.detail.DetailPublishResult;
import com.maibao.tech.usage.util.CostProperties;

import io.micrometer.core.instrument.MeterRegistry;

@Configuration
public class RabbitConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitConfig.class);

    /** returns 回调里最多打多少字符的消息体，避免一条大消息刷爆日志 */
    private static final int MAX_RETURNED_BODY = 4000;

    @Value("${app.mq.exchange}")
    private String exchangeName;

    @Value("${app.mq.queue}")
    private String queueName;

    @Value("${app.mq.routing-key}")
    private String routingKey;

    @Value("${app.mq.dlx-exchange}")
    private String dlxExchange;

    @Value("${app.mq.dlq-queue}")
    private String dlqQueue;

    @Value("${app.mq.dlq-routing-key}")
    private String dlqRoutingKey;

    /**
     * 队列类型：{@code quorum}（Raft 复制，RabbitMQ 4.x 推荐）或 {@code classic}。
     *
     * <p>切 quorum 只需加一个 {@code x-queue-type} 参数，但<b>已存在的同名 classic 队列必须先删除</b>，
     * 否则声明会报 PRECONDITION_FAILED。本地调试或旧 broker 可临时改成 classic。</p>
     */
    @Value("${app.mq.queue-type:quorum}")
    private String queueType;

    private QueueBuilder durableQueue(String name) {
        QueueBuilder builder = QueueBuilder.durable(name);
        return "quorum".equalsIgnoreCase(queueType) ? builder.quorum() : builder;
    }

    // ---------- 任务链路（当前停用，资产保留） ----------

    @Bean
    public DirectExchange taskExchange() {
        return new DirectExchange(exchangeName, true, false);
    }

    /** 主队列绑定死信交换机：重试耗尽的 nack 会被路由到 DLQ，而不是凭空消失 */
    @Bean
    public Queue taskQueue() {
        return durableQueue(queueName)
                .deadLetterExchange(dlxExchange)
                .deadLetterRoutingKey(dlqRoutingKey)
                .build();
    }

    @Bean
    public Binding taskBinding(@Qualifier("taskQueue") Queue queue,
                               @Qualifier("taskExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(routingKey);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(dlxExchange, true, false);
    }

    @Bean
    public Queue deadLetterQueue() {
        return durableQueue(dlqQueue).build();
    }

    @Bean
    public Binding deadLetterBinding(@Qualifier("deadLetterQueue") Queue queue,
                                     @Qualifier("deadLetterExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(dlqRoutingKey);
    }

    // ---------- 明细落库：独立拓扑，量级是"每 200ms 一条批量消息" ----------

    @Bean
    public DirectExchange detailExchange(CostProperties properties) {
        return new DirectExchange(properties.getDetail().getExchange(), true, false);
    }

    @Bean
    public Queue detailQueue(CostProperties properties) {
        return durableQueue(properties.getDetail().getQueue())
                .deadLetterExchange(properties.getDetail().getDlxExchange())
                .deadLetterRoutingKey(properties.getDetail().getDlqRoutingKey())
                .build();
    }

    @Bean
    public Binding detailBinding(@Qualifier("detailQueue") Queue queue,
                                 @Qualifier("detailExchange") DirectExchange exchange,
                                 CostProperties properties) {
        return BindingBuilder.bind(queue).to(exchange).with(properties.getDetail().getRoutingKey());
    }

    @Bean
    public DirectExchange detailDeadLetterExchange(CostProperties properties) {
        return new DirectExchange(properties.getDetail().getDlxExchange(), true, false);
    }

    @Bean
    public Queue detailDeadLetterQueue(CostProperties properties) {
        return durableQueue(properties.getDetail().getDlqQueue()).build();
    }

    @Bean
    public Binding detailDeadLetterBinding(@Qualifier("detailDeadLetterQueue") Queue queue,
                                           @Qualifier("detailDeadLetterExchange") DirectExchange exchange,
                                           CostProperties properties) {
        return BindingBuilder.bind(queue).to(exchange).with(properties.getDetail().getDlqRoutingKey());
    }

    /**
     * 明细重试的退避拓扑：每个档位一个队列，只设 TTL、没有消费者。
     *
     * <p>消息进队列后等 TTL 到期，被死信回主交换机 —— 等价于"延迟 N 秒后重投"。
     * 用队列级 {@code x-message-ttl} 而不是消息级 {@code expiration}，是为了避开
     * "队头消息 TTL 未到，后面早过期的也出不去"的队头阻塞。</p>
     */
    @Bean
    public Declarables detailRetryTopology(CostProperties properties) {
        CostProperties.Detail detail = properties.getDetail();
        DirectExchange retryExchange = new DirectExchange(detail.getRetryExchange(), true, false);
        List<Declarable> declarables = new ArrayList<>();
        declarables.add(retryExchange);
        for (long delayMs : detail.getRetryDelaysMs()) {
            String name = detail.retryQueue(delayMs);
            Queue queue = durableQueue(name)
                    .ttl((int) Math.min(delayMs, Integer.MAX_VALUE))
                    .deadLetterExchange(detail.getExchange())
                    .deadLetterRoutingKey(detail.getRoutingKey())
                    .build();
            declarables.add(queue);
            declarables.add(BindingBuilder.bind(queue).to(retryExchange)
                    .with(detail.retryRoutingKey(delayMs)));
        }
        return new Declarables(declarables);
    }

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 生产端确认与退回。
     *
     * <ul>
     *   <li><b>confirm</b>：明细消息（correlation 以 {@code detail:} 开头）转成事件交给
     *       {@code DetailBuffer} 决定重发还是落日志；outbox 消息（纯数字 id）仍走 outbox 状态机。</li>
     *   <li><b>returns</b>：消息没路由到任何队列（配置错），把<b>完整消息体</b>写进 ERROR 日志 ——
     *       同时落 {@code logs/error.YYYY-MM-DD.log}，数据不至于只留一行"unroutable"。</li>
     * </ul>
     */
    @Bean
    public RabbitTemplateCustomizer reliablePublishCustomizer(MeterRegistry meterRegistry,
                                                              OutboxRepository outboxRepository,
                                                              ApplicationEventPublisher events) {
        return template -> {
            template.setMandatory(true);
            template.setConfirmCallback((correlation, ack, cause) -> {
                meterRegistry.counter("mq.publish.confirm", "result", ack ? "ack" : "nack").increment();
                if (correlation == null || correlation.getId() == null) {
                    return;
                }
                String id = correlation.getId();
                if (id.startsWith(DetailBuffer.CORRELATION_PREFIX)) {
                    String batchId = id.substring(DetailBuffer.CORRELATION_PREFIX.length());
                    events.publishEvent(new DetailPublishResult(batchId, ack, cause));
                    if (!ack) {
                        log.error("detail publish not confirmed: batchId={} cause={}", batchId, cause);
                    }
                    return;
                }
                long outboxId;
                try {
                    outboxId = Long.parseLong(id);
                } catch (NumberFormatException notOutbox) {
                    return;
                }
                if (ack) {
                    outboxRepository.markSent(outboxId, OutboxMessage.SENT, OutboxMessage.PENDING, Instant.now());
                } else {
                    outboxRepository.markRetry(outboxId, OutboxMessage.PENDING,
                            Instant.now().plusSeconds(5), "not confirmed: " + cause, Instant.now());
                    log.error("publish not confirmed: outboxId={} cause={}", outboxId, cause);
                }
            });
            template.setReturnsCallback(returned -> {
                meterRegistry.counter("mq.publish.returned").increment();
                String messageId = returned.getMessage().getMessageProperties().getMessageId();
                String body = new String(returned.getMessage().getBody(), StandardCharsets.UTF_8);
                log.error("message unroutable, payload lost unless handled: exchange={} routingKey={} reply={} messageId={} payload={}",
                        returned.getExchange(), returned.getRoutingKey(), returned.getReplyText(),
                        messageId, abbreviate(body));
                if (messageId != null) {
                    outboxRepository.findByMessageId(messageId).ifPresent(row ->
                            outboxRepository.markFailed(row.getId(), OutboxMessage.FAILED,
                                    "unroutable: " + returned.getReplyText(), Instant.now()));
                }
            });
        };
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= MAX_RETURNED_BODY
                ? text
                : text.substring(0, MAX_RETURNED_BODY) + "...<truncated>";
    }
}
