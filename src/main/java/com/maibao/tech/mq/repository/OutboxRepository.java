package com.maibao.tech.mq.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.maibao.tech.mq.domain.OutboxMessage;

public interface OutboxRepository extends JpaRepository<OutboxMessage, Long> {

    Optional<OutboxMessage> findByMessageId(String messageId);

    List<OutboxMessage> findTop20ByStatusAndNextRetryAtLessThanEqualOrderByIdAsc(String status, Instant now);

    /** 已投递、等待 confirm：只把下次检查时间往后推，避免扫描线程重复发送 */
    @Modifying
    @Transactional
    @Query("update OutboxMessage o set o.nextRetryAt = :nextCheck, o.updatedAt = :now where o.id = :id")
    int markPublished(@Param("id") Long id, @Param("nextCheck") Instant nextCheck, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update OutboxMessage o set o.status = :sent, o.updatedAt = :now where o.id = :id and o.status = :pending")
    int markSent(@Param("id") Long id, @Param("sent") String sent, @Param("pending") String pending,
                 @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update OutboxMessage o set o.status = :pending, o.attempts = o.attempts + 1, "
            + "o.nextRetryAt = :nextRetryAt, o.lastError = :error, o.updatedAt = :now where o.id = :id")
    int markRetry(@Param("id") Long id, @Param("pending") String pending,
                  @Param("nextRetryAt") Instant nextRetryAt, @Param("error") String error,
                  @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update OutboxMessage o set o.status = :failed, o.lastError = :error, o.updatedAt = :now where o.id = :id")
    int markFailed(@Param("id") Long id, @Param("failed") String failed, @Param("error") String error,
                   @Param("now") Instant now);

    /** 消费成功后回执；重复投递也会走到这里，幂等 */
    @Modifying
    @Transactional
    @Query("update OutboxMessage o set o.status = :consumed, o.updatedAt = :now "
            + "where o.messageId = :messageId and o.status in (:sent, :pending)")
    int markConsumed(@Param("messageId") String messageId, @Param("consumed") String consumed,
                     @Param("sent") String sent, @Param("pending") String pending, @Param("now") Instant now);

    /** 对账：SENT 太久没有消费回执 → 打回 PENDING 重投 */
    @Modifying
    @Transactional
    @Query("update OutboxMessage o set o.status = :pending, o.attempts = o.attempts + 1, "
            + "o.nextRetryAt = :now, o.lastError = 'no consumption receipt', o.updatedAt = :now "
            + "where o.status = :sent and o.updatedAt < :threshold")
    int requeueStale(@Param("pending") String pending, @Param("sent") String sent,
                     @Param("threshold") Instant threshold, @Param("now") Instant now);

    /** 重试次数用尽的 PENDING 标记为 FAILED，避免无限重投 */
    @Modifying
    @Transactional
    @Query("update OutboxMessage o set o.status = :failed, o.lastError = 'max attempts reached', "
            + "o.updatedAt = :now where o.status = :pending and o.attempts >= :maxAttempts")
    int failExhausted(@Param("pending") String pending, @Param("failed") String failed,
                      @Param("maxAttempts") int maxAttempts, @Param("now") Instant now);
}
