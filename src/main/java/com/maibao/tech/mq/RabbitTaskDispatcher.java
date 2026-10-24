package com.maibao.tech.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 真正的异步链路：把任务投递到 RabbitMQ。
 *
 * <p>消息设置持久化 + messageId（便于幂等与排查）；投递失败直接抛异常，
 * 由调用方感知，不做任何「降级为同步」的处理。</p>
 */
@Component
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
    public void dispatch(TaskMessage message) {
        rabbitTemplate.convertAndSend(exchange, routingKey, message, m -> {
            m.getMessageProperties().setMessageId(message.taskId());
            m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return m;
        });
        log.info("sent task {} to mq", message.taskId());
    }
}
