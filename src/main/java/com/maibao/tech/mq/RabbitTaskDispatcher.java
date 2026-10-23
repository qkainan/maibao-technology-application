package com.maibao.tech.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 真正的异步链路：发送到 RabbitMQ。 */
@Component
@ConditionalOnProperty(prefix = "app.mq", name = "enabled", havingValue = "true")
public class RabbitTaskDispatcher implements TaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RabbitTaskDispatcher.class);

    private final RabbitTemplate rabbitTemplate;

    @Value("${app.mq.exchange}")
    private String exchange;

    @Value("${app.mq.routing-key}")
    private String routingKey;

    public RabbitTaskDispatcher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public boolean dispatch(TaskMessage message) {
        rabbitTemplate.convertAndSend(exchange, routingKey, message, m -> {
            m.getMessageProperties().setMessageId(message.taskId());
            m.getMessageProperties().setDeliveryMode(
                    org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
            return m;
        });
        log.info("sent task {} to mq", message.taskId());
        return true;
    }
}
