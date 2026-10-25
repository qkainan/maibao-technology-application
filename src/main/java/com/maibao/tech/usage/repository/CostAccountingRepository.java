package com.maibao.tech.usage.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.maibao.tech.usage.domain.po.CostAccounting;

/**
 * 成本聚合表的只读访问。
 *
 * <p><b>这里没有写方法，是有意的</b>：累加走 Redis（{@code CostRedisRepository.increase}），
 * 落库走 {@code CostFlushJob} 里的 {@code INSERT ... ON DUPLICATE KEY UPDATE} 覆盖写。
 * 早期的 {@code accumulate}（{@code UPDATE ... += ?}）和 {@code sumByStatDate}
 * 在改成覆盖写之后已无调用方，删除。</p>
 */
public interface CostAccountingRepository extends JpaRepository<CostAccounting, Long> {

    List<CostAccounting> findByUserIdOrderByStatDateDescModelAsc(String userId);
}
