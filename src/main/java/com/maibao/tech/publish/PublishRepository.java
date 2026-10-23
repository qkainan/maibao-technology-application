package com.maibao.tech.publish;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PublishRepository extends JpaRepository<PublishRecord, Long> {

    /**
     * 乐观锁条件更新：只有当前状态等于 from 才改成 to。
     *
     * <p>返回 0 表示被别人抢先推进了 —— 这就是"状态字段当锁用"。</p>
     */
    @Modifying
    @Transactional
    @Query("update PublishRecord p set p.status = :to where p.id = :id and p.status = :from")
    int casStatus(@Param("id") Long id,
                  @Param("from") PublishStatus from,
                  @Param("to") PublishStatus to);

    @Modifying
    @Transactional
    @Query("update PublishRecord p set p.status = :to, p.failReason = :reason "
            + "where p.id = :id and p.status = :from")
    int casStatusWithReason(@Param("id") Long id,
                            @Param("from") PublishStatus from,
                            @Param("to") PublishStatus to,
                            @Param("reason") String reason);

    /** 仅供并发用例复位：把记录强制拉回 SCHEDULED，便于重复跑 */
    @Modifying
    @Transactional
    @Query("update PublishRecord p set p.status = :to where p.id = :id")
    int forceStatus(@Param("id") Long id, @Param("to") PublishStatus to);

    List<PublishRecord> findByStatusAndScheduledAtLessThanEqual(PublishStatus status, Instant now);
}
