package com.maibao.tech.mq;

import com.maibao.tech.mq.outbox.repository.OutboxRepository;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.maibao.tech.mq.outbox.domain.OutboxMessage;

import io.micrometer.core.instrument.MeterRegistry;

@Configuration
public class RabbitConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitConfig.class);

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







    @Bean
    public DirectExchange taskExchange() {
        return new DirectExchange(exchangeName, true, false);
    }

    /** 主队列绑定死信交换机：重试耗尽的 nack 会被路由到 DLQ，而不是凭空消失 */
    @Bean
    public Queue taskQueue() {
        return QueueBuilder.durable(queueName)
                .deadLetterExchange(dlxExchange)
                .deadLetterRoutingKey(dlqRoutingKey)
                .build();
    }

    @Bean
    public Binding taskBinding(Queue taskQueue, DirectExchange taskExchange) {
        return BindingBuilder.bind(taskQueue).to(taskExchange).with(routingKey);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(dlxExchange, true, false);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(dlqQueue).build();
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(dlqRoutingKey);
    }

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 生产端确认与退回：confirm 决定 outbox 是 SENT 还是重试；
     * returns 说明消息没路由到任何队列（配置错，标记失败并告警）。
     */
    @Bean
    public RabbitTemplateCustomizer reliablePublishCustomizer(MeterRegistry meterRegistry,
                                                              OutboxRepository outboxRepository) {
        return template -> {
            template.setMandatory(true);
            template.setConfirmCallback((correlation, ack, cause) -> {
                meterRegistry.counter("mq.publish.confirm", "result", ack ? "ack" : "nack").increment();
                if (correlation == null || correlation.getId() == null) {
                    return;
                }
                long outboxId = Long.parseLong(correlation.getId());
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
                log.error("message unroutable: exchange={} routingKey={} reply={}",
                        returned.getExchange(), returned.getRoutingKey(), returned.getReplyText());
                if (messageId != null) {
                    outboxRepository.findByMessageId(messageId).ifPresent(row ->
                            outboxRepository.markFailed(row.getId(), OutboxMessage.FAILED,
                                    "unroutable: " + returned.getReplyText(), Instant.now()));
                }
            });
        };
    }
}
