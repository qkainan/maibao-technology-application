package com.maibao.tech.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Bean
    public DirectExchange taskExchange() {
        return new DirectExchange(exchangeName, true, false);
    }

    @Bean
    public Queue taskQueue() {
        return new Queue(queueName, true);
    }

    @Bean
    public Binding taskBinding(Queue taskQueue, DirectExchange taskExchange) {
        return BindingBuilder.bind(taskQueue).to(taskExchange).with(routingKey);
    }

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 生产端确认与退回。
     *
     * <p>confirm 回答「broker 收下没有」，return 回答「收下了但有没有队列接收」——
     * 两者都缺的时候，消息发出去是死是活调用方完全不知道。</p>
     */
    @Bean
    public RabbitTemplateCustomizer reliablePublishCustomizer(MeterRegistry meterRegistry) {
        return template -> {
            template.setMandatory(true);
            template.setConfirmCallback((correlation, ack, cause) -> {
                meterRegistry.counter("mq.publish.confirm", "result", ack ? "ack" : "nack").increment();
                if (!ack) {
                    log.error("publish not confirmed: id={} cause={}",
                            correlation == null ? "-" : correlation.getId(), cause);
                }
            });
            template.setReturnsCallback(returned -> {
                meterRegistry.counter("mq.publish.returned").increment();
                log.error("message unroutable: exchange={} routingKey={} reply={}",
                        returned.getExchange(), returned.getRoutingKey(), returned.getReplyText());
            });
        };
    }
}
