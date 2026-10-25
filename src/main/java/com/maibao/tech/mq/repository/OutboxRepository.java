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

    long countByStatus(String status);

    /** 投递持续失败的健康度指标：PENDING 且 attempts 已经不少 */
    @Query("select count(o) from OutboxMessage o where o.status = :pending and o.attempts >= :attempts")
    long countByStatusAndAttemptsAtLeast(@Param("pending") String pending,
                                         @Param("attempts") int attempts);

    /**
     * 用 next_retry_at 当租约做 CAS 抢占：只有一个实例能把「PENDING 且已到期」的行推到未来。
     * 返回 1 才由本实例发送；返回 0 说明别的实例先抢到了，直接跳过。
     *
     * <p>多实例同时扫同一张 outbox 时，这一步是防止重复投递的关键：扫描本身有竞态，
     * 但 UPDATE 只有一行能成功。</p>
     */
    @Modifying
    @Transactional
    @Query("update OutboxMessage o set o.nextRetryAt = :leaseUntil, o.updatedAt = :now "
            + "where o.id = :id and o.status = :pending and o.nextRetryAt <= :now")
    int claim(@Param("id") Long id, @Param("pending") String pending,
              @Param("leaseUntil") Instant leaseUntil, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update OutboxMessage o set o.status = :sent, o.updatedAt = :now where o.id = :id and o.status = :pending")
    int markSent(@Param("id") Long id, @Param("sent") String sent, @Param("pending") String pending,
                 @Param("now") Instant now);

    /**
     * 发送失败退避重试。
     *
     * <p>状态护栏是必须的：同一条 outbox 因租约到期可能被 publish 两次（同一个
     * CorrelationData id），第二次的 nack 不能把已经 CONSUMED / FAILED 的行打回 PENDING。</p>
     */
    @Modifying
    @Transactional
    @Query("update OutboxMessage o set o.status = :pending, o.attempts = o.attempts + 1, "
            + "o.nextRetryAt = :nextRetryAt, o.lastError = :error, o.updatedAt = :now "
            + "where o.id = :id and o.status in ('PENDING', 'SENT')")
    int markRetry(@Param("id") Long id, @Param("pending") String pending,
                  @Param("nextRetryAt") Instant nextRetryAt, @Param("error") String error,
                  @Param("now") Instant now);

    /** 消息未路由（配置错）：只有还在途中的行才能标失败 */
    @Modifying
    @Transactional
    @Query("update OutboxMessage o set o.status = :failed, o.lastError = :error, o.updatedAt = :now "
            + "where o.id = :id and o.status in ('PENDING', 'SENT')")
    int markFailed(@Param("id") Long id, @Param("failed") String failed, @Param("error") String error,
                   @Param("now") Instant now);

    /**
     * 消费终态失败（不可重试或重试耗尽）：按 messageId 终结 outbox。
     *
     * <p>不写回的话，outbox 会一直停在 SENT，对账 5 分钟后把它打回 PENDING 重投，
     * 同一条注定失败的消息会被反复执行，直到 outbox 自己的 attempts 用完。</p>
     */
    @Modifying
    @Transactional
    @Query("update OutboxMessage o set o.status = :failed, o.lastError = :error, o.updatedAt = :now "
            + "where o.messageId = :messageId and o.status in ('PENDING', 'SENT')")
    int markFailedByMessageId(@Param("messageId") String messageId, @Param("failed") String failed,
                              @Param("error") String error, @Param("now") Instant now);

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
