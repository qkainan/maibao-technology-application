package com.maibao.tech.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "app.mq", name = "enabled", havingValue = "true")
public class RabbitConfig {

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
}
