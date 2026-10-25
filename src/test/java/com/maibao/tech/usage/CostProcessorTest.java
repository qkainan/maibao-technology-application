package com.maibao.tech.usage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.maibao.tech.usage.domain.CostMessage;

@ExtendWith(MockitoExtension.class)
class CostProcessorTest {

    private static final CostMessage MESSAGE = new CostMessage(
            "biz-1", "u1", "mock:echo", LocalDate.of(2026, 10, 25),
            3, 4, new BigDecimal("0.001"), "CNY");

    @Mock
    private CostDedupeService dedupe;

    @Mock
    private CostAccumulator accumulator;

    private CostProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new CostProcessor(dedupe, accumulator);
    }

    @Test
    void should_mark_message_then_accumulate() {
        processor.process(MESSAGE);

        verify(dedupe).mark("biz-1");
        verify(accumulator).accumulate("u1", "mock:echo", LocalDate.of(2026, 10, 25),
                3, 4, new BigDecimal("0.001"));
        verify(dedupe, never()).unmark(any());
    }

    @Test
    void should_unmark_and_rethrow_when_accumulate_fails() {
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(accumulator).accumulate(eq("u1"), eq("mock:echo"), any(), eq(3), eq(4), any());

        assertThatThrownBy(() -> processor.process(MESSAGE))
                .isInstanceOf(IllegalStateException.class);

        verify(dedupe).unmark("biz-1");
    }
}
