package com.maibao.tech.usage.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.maibao.tech.usage.domain.po.UserQueryLog;

public interface UserQueryLogRepository extends JpaRepository<UserQueryLog, Long> {

    List<UserQueryLog> findByUserIdOrderByIdDesc(String userId, Pageable pageable);

    List<UserQueryLog> findAllByOrderByIdDesc(Pageable pageable);

    /** 某时间窗内成功调用的成本合计，用于和聚合表对账 */
    @Query("select coalesce(sum(l.costAmount), 0) from UserQueryLog l "
            + "where l.status = 'SUCCESS' and l.createdAt >= :from and l.createdAt < :to")
    BigDecimal sumSuccessCost(@Param("from") Instant from, @Param("to") Instant to);

    @Query("select count(l) from UserQueryLog l "
            + "where l.status = 'SUCCESS' and l.createdAt >= :from and l.createdAt < :to")
    long countSuccess(@Param("from") Instant from, @Param("to") Instant to);
}
