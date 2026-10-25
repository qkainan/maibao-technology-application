package com.maibao.tech.usage.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.maibao.tech.usage.domain.CostMessageLog;

public interface CostMessageLogRepository extends JpaRepository<CostMessageLog, Long> {

    long countByCreatedAtBetween(Instant from, Instant to);

    /** 清理 7 天前的去重记录：它只防重复，不需要长期保留 */
    @Modifying
    @Transactional
    @Query("delete from CostMessageLog c where c.createdAt < :threshold")
    int deleteOlderThan(@Param("threshold") Instant threshold);

    /** 累加失败时撤销幂等锚点，让重试能重跑 */
    @Modifying
    @Transactional
    @Query("delete from CostMessageLog c where c.messageId = :messageId")
    int deleteByMessageId(@Param("messageId") String messageId);
}
