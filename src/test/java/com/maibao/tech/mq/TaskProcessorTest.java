package com.maibao.tech.mq;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.maibao.tech.adapter.domain.ProviderError;
import com.maibao.tech.adapter.domain.ProviderException;
import com.maibao.tech.chat.ChatService;
import com.maibao.tech.mq.domain.TaskMessage;

@ExtendWith(MockitoExtension.class)
class TaskProcessorTest {

    @Mock
    private TaskDedupeService dedupe;

    @Mock
    private ChatService chatService;

    private TaskProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new TaskProcessor(dedupe, chatService, "mock:echo", "async-task");
    }

    @Test
    void should_call_model_and_record_usage_with_async_source() {
        when(chatService.chat("async-task", "", "mock:echo", "hi", "ASYNC")).thenReturn("[mock] 收到：hi");

        processor.process(TaskMessage.of("t1", "hi"));

        verify(dedupe).markProcessed("t1");
        verify(chatService).chat("async-task", "", "mock:echo", "hi", "ASYNC");
        verify(dedupe, never()).unmark(anyString());
    }

    @Test
    void should_prefer_model_and_user_from_message() {
        when(chatService.chat("u9", "", "openai:gpt-4o", "hi", "ASYNC")).thenReturn("ok");

        processor.process(TaskMessage.of("t2", "hi", "openai:gpt-4o", "u9"));

        verify(chatService).chat("u9", "", "openai:gpt-4o", "hi", "ASYNC");
    }

    @Test
    void should_unmark_and_rethrow_when_business_fails() {
        when(chatService.chat(any(), any(), any(), any(), any()))
                .thenThrow(new ProviderException(ProviderError.TIMEOUT, "upstream timeout"));

        assertThatThrownBy(() -> processor.process(TaskMessage.of("t3", "hi")))
                .isInstanceOf(ProviderException.class);

        verify(dedupe).unmark("t3");
    }
}
