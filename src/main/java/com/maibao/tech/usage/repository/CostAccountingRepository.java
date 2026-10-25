package com.maibao.tech.usage.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.maibao.tech.usage.domain.CostAccounting;

public interface CostAccountingRepository extends JpaRepository<CostAccounting, Long> {

    List<CostAccounting> findByUserIdOrderByStatDateDescModelAsc(String userId);

    /**
     * 原子累加：并发下不会丢计数，也不需要在应用层加锁。
     *
     * @return 0 表示这一行还不存在，由调用方决定是否插入
     */
    @Modifying
    @Transactional
    @Query("update CostAccounting c set c.requestCount = c.requestCount + 1, "
            + "c.promptTokens = c.promptTokens + :pt, c.completionTokens = c.completionTokens + :ct, "
            + "c.totalTokens = c.totalTokens + :tt, c.costAmount = c.costAmount + :cost, "
            + "c.updatedAt = :now "
            + "where c.userId = :uid and c.model = :model and c.statDate = :date")
    int accumulate(@Param("uid") String userId,
                   @Param("model") String model,
                   @Param("date") LocalDate date,
                   @Param("pt") long promptTokens,
                   @Param("ct") long completionTokens,
                   @Param("tt") long totalTokens,
                   @Param("cost") BigDecimal cost,
                   @Param("now") Instant now);
}
