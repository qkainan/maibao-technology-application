package com.maibao.tech.mq;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

/**
 * 消费者：手动 ack + 幂等。
 *
 * <p>三件套齐全：生产端 confirm（配置里开）、消息持久化（dispatcher 里设）、
 * 消费端手动 ack（这里）。重复投递靠 processed_task 的唯一约束挡住。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.mq", name = "enabled", havingValue = "true")
public class TaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(TaskConsumer.class);

    private final ProcessedTaskRepository repository;

    public TaskConsumer(ProcessedTaskRepository repository) {
        this.repository = repository;
    }

    @RabbitListener(queues = "${app.mq.queue}")
    public void onMessage(TaskMessage message, Message raw, Channel channel) throws IOException {
        long tag = raw.getMessageProperties().getDeliveryTag();
        try {
            if (repository.existsByTaskId(message.taskId())) {
                log.info("duplicate message, ack and skip: {}", message.taskId());
                channel.basicAck(tag, false);
                return;
            }
            repository.save(new ProcessedTask(message.taskId()));
            handle(message);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("consume failed: {}", message.taskId(), e);
            // 不重回队列，避免毒消息打爆队列；交给死信/人工处理
            channel.basicNack(tag, false, false);
        }
    }

    private void handle(TaskMessage message) {
        log.info("[mq] handle task {} payload={}", message.taskId(), message.payload());
    }
}
