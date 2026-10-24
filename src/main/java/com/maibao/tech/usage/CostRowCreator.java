package com.maibao.tech.usage;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.maibao.tech.usage.domain.CostAccounting;

/**
 * 成本行的"插入"。
 *
 * <p>单独一个 Bean 且用 {@code REQUIRES_NEW}，是为了让"并发插入撞唯一键"这件事
 * 只回滚这个内层事务，外层还能退回去做累加——否则整个事务会被标记 rollback-only。</p>
 */
@Component
public class CostRowCreator {

    private final CostAccountingRepository repository;

    public CostRowCreator(CostAccountingRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void create(String userId, String model, LocalDate date,
                       int promptTokens, int completionTokens, BigDecimal cost, String currency) {
        repository.save(new CostAccounting(userId, model, date, 1,
                promptTokens, completionTokens, cost, currency));
    }
}
