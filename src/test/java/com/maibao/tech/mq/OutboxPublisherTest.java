package com.maibao.tech.mq;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.maibao.tech.mq.domain.OutboxMessage;
import com.maibao.tech.mq.repository.OutboxRepository;

/** 验证多实例下的租约抢占：只有 claim 返回 1 的实例才发送。 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxRepository repository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private OutboxPublisher publisher;
    private OutboxMessage row;

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(repository, rabbitTemplate, 5, 300, 30);
        row = new OutboxMessage("t1", "ex", "rk", "{\"bizId\":\"t1\"}");
    }

    @Test
    void should_send_when_claim_succeeds() throws Exception {
        givenPendingRow();
        when(repository.claim(isNull(), eq(OutboxMessage.PENDING), any(), any())).thenReturn(1);

        publisher.publishPending();

        verify(rabbitTemplate).send(eq("ex"), eq("rk"), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void should_skip_when_row_claimed_by_another_instance() throws Exception {
        givenPendingRow();
        when(repository.claim(isNull(), eq(OutboxMessage.PENDING), any(), any())).thenReturn(0);

        publisher.publishPending();

        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class),
                any(CorrelationData.class));
    }

    @Test
    void should_schedule_retry_when_send_throws() throws Exception {
        givenPendingRow();
        when(repository.claim(isNull(), eq(OutboxMessage.PENDING), any(), any())).thenReturn(1);
        doThrow(new AmqpException("broker down")).when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        publisher.publishPending();

        verify(repository).markRetry(isNull(), eq(OutboxMessage.PENDING), any(),
                contains("broker down"), any());
    }

    private void givenPendingRow() {
        when(repository.findTop20ByStatusAndNextRetryAtLessThanEqualOrderByIdAsc(
                eq(OutboxMessage.PENDING), any())).thenReturn(List.of(row));
    }
}
