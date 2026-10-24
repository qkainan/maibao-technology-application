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

    /**
     * 抢占：改状态的同时记下抢占时刻，两者必须在同一条 UPDATE 里，
     * 否则「状态已变但时间没写」的记录会被超时回收误判。
     */
    @Modifying
    @Transactional
    @Query("update PublishRecord p set p.status = :to, p.claimedAt = :now "
            + "where p.id = :id and p.status = :from")
    int claim(@Param("id") Long id,
              @Param("from") PublishStatus from,
              @Param("to") PublishStatus to,
              @Param("now") Instant now);

    /** 卡死扫描：PUBLISHING 且抢占时刻早于 deadline（老数据没有 claimedAt 也算卡死） */
    @Query("select p from PublishRecord p where p.status = :status "
            + "and (p.claimedAt is null or p.claimedAt < :deadline)")
    List<PublishRecord> findByStatusAndClaimedAtBefore(@Param("status") PublishStatus status,
                                                       @Param("deadline") Instant deadline);

    /** 仅供并发用例复位：把记录强制拉回 SCHEDULED，便于重复跑 */
    @Modifying
    @Transactional
    @Query("update PublishRecord p set p.status = :to where p.id = :id")
    int forceStatus(@Param("id") Long id, @Param("to") PublishStatus to);

    List<PublishRecord> findByStatusAndScheduledAtLessThanEqual(PublishStatus status, Instant now);
}
