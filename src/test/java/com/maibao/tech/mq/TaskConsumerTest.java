package com.maibao.tech.mq;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataIntegrityViolationException;

import com.rabbitmq.client.Channel;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.maibao.tech.adapter.domain.ProviderError;
import com.maibao.tech.adapter.domain.ProviderException;
import com.maibao.tech.mq.domain.FailedTask;
import com.maibao.tech.mq.domain.TaskMessage;

@ExtendWith(MockitoExtension.class)
class TaskConsumerTest {

    private static final long DELIVERY_TAG = 7L;
    private static final int MAX_RETRIES = 3;

    @Mock
    private TaskProcessor processor;

    @Mock
    private FailedTaskRepository failedTaskRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private Channel channel;

    private TaskConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TaskConsumer(processor, failedTaskRepository, outboxRepository, rabbitTemplate,
                new SimpleMeterRegistry(), "ex", "rk", MAX_RETRIES);
    }

    @Test
    void should_ack_and_skip_when_task_already_processed() throws Exception {
        willThrow(new DataIntegrityViolationException("duplicate"))
                .given(processor).process(any(TaskMessage.class));

        consumer.onMessage(task(), rawMessage(0), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class));
    }

    @Test
    void should_republish_with_incremented_retry_count() throws Exception {
        willThrow(new IllegalStateException("boom"))
                .given(processor).process(any(TaskMessage.class));

        consumer.onMessage(task(), rawMessage(0), channel);

        verify(rabbitTemplate).convertAndSend(eq("ex"), eq("rk"), any(),
                any(MessagePostProcessor.class));
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(failedTaskRepository, never()).save(any(FailedTask.class));
    }

    @Test
    void should_record_failed_task_after_retries_exhausted() throws Exception {
        willThrow(new IllegalStateException("boom"))
                .given(processor).process(any(TaskMessage.class));

        consumer.onMessage(task(), rawMessage(MAX_RETRIES), channel);

        verify(failedTaskRepository).save(any(FailedTask.class));
        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class));
    }

    @Test
    void should_not_retry_auth_errors() throws Exception {
        willThrow(new ProviderException(ProviderError.AUTH, "invalid key"))
                .given(processor).process(any(TaskMessage.class));

        consumer.onMessage(task(), rawMessage(0), channel);

        verify(failedTaskRepository).save(any(FailedTask.class));
        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class));
    }

    private TaskMessage task() {
        return TaskMessage.of("t1", "payload");
    }
    private Message rawMessage(int attempt) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(DELIVERY_TAG);
        if (attempt > 0) {
            properties.setHeader(TaskConsumer.RETRY_HEADER, attempt);
        }
        return new Message(new byte[0], properties);
    }
}
