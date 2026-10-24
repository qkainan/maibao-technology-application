package com.maibao.tech.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PublishServiceTest {

    @Mock
    private PublishRepository repository;

    @Mock
    private PublishExecutor executor;

    @Mock
    private PublishHandler handler;

    private PublishService service;

    @BeforeEach
    void setUp() {
        service = new PublishService(repository, executor, handler, 60);
    }

    @Test
    void should_submit_execution_only_for_claimed_records() {
        given(repository.findByStatusAndScheduledAtLessThanEqual(eq(PublishStatus.SCHEDULED), any(Instant.class)))
                .willReturn(List.of(record(1L), record(2L)));
        given(repository.claim(eq(1L), eq(PublishStatus.SCHEDULED), eq(PublishStatus.PUBLISHING), any(Instant.class)))
                .willReturn(1);
        given(repository.claim(eq(2L), eq(PublishStatus.SCHEDULED), eq(PublishStatus.PUBLISHING), any(Instant.class)))
                .willReturn(0);

        assertThat(service.scanAndClaimDue()).isEqualTo(1);
        verify(executor).submit(any(Runnable.class));
    }

    @Test
    void should_advance_to_success_when_handler_succeeds() throws Exception {
        given(repository.findById(1L)).willReturn(Optional.of(record(1L)));
        given(repository.casStatusWithReason(1L, PublishStatus.PUBLISHING, PublishStatus.SUCCESS, null)).willReturn(1);

        service.execute(1L);

        verify(handler).publish(any(PublishRecord.class));
        verify(repository).casStatusWithReason(1L, PublishStatus.PUBLISHING, PublishStatus.SUCCESS, null);
    }

    @Test
    void should_advance_to_failed_when_handler_throws() throws Exception {
        given(repository.findById(1L)).willReturn(Optional.of(record(1L)));
        willThrow(new IllegalStateException("boom")).given(handler).publish(any(PublishRecord.class));
        given(repository.casStatusWithReason(eq(1L), eq(PublishStatus.PUBLISHING), eq(PublishStatus.FAILED), anyString()))
                .willReturn(1);

        service.execute(1L);

        verify(repository).casStatusWithReason(eq(1L), eq(PublishStatus.PUBLISHING), eq(PublishStatus.FAILED), anyString());
    }

    @Test
    void should_report_finish_lost_when_state_already_changed() {
        // 已经被超时回收或重复收尾：影响行数为 0
        given(repository.casStatusWithReason(1L, PublishStatus.PUBLISHING, PublishStatus.SUCCESS, null)).willReturn(0);

        assertThat(service.finish(1L, true, null)).isFalse();
    }

    @Test
    void should_recover_records_stuck_in_publishing() {
        given(repository.findByStatusAndClaimedAtBefore(eq(PublishStatus.PUBLISHING), any(Instant.class)))
                .willReturn(List.of(record(9L)));
        given(repository.casStatusWithReason(eq(9L), eq(PublishStatus.PUBLISHING), eq(PublishStatus.FAILED), anyString()))
                .willReturn(1);

        assertThat(service.recoverStuck()).isEqualTo(1);
    }

    private PublishRecord record(long id) {
        PublishRecord record = new PublishRecord("node-" + id, null);
        ReflectionTestUtils.setField(record, "id", id);
        return record;
    }
}
