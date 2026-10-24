package com.maibao.tech.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 成本累加：先尝试 UPDATE，影响 0 行再插入；插入撞唯一键说明并发抢先，退回去累加。
 *
 * <p><b>这里刻意不加 @Transactional。</b>MySQL 的 REPEATABLE READ 下，UPDATE 没命中行时会在
 * 唯一索引上留下间隙锁；如果 UPDATE 和 INSERT 在同一个事务里，INSERT 会被自己事务的间隙锁
 * 挡住，直到锁等待超时。让 UPDATE 独立提交，锁就释放了。</p>
 *
 * <p>并发插入由唯一键兜底：撞键说明别人先建了行，回到累加即可。</p>
 */
@Service
public class CostAccumulator {

    private final CostAccountingRepository repository;
    private final CostRowCreator rowCreator;
    private final CostCalculator calculator;

    public CostAccumulator(CostAccountingRepository repository,
                           CostRowCreator rowCreator,
                           CostCalculator calculator) {
        this.repository = repository;
        this.rowCreator = rowCreator;
        this.calculator = calculator;
    }

    public void accumulate(String userId, String model, int promptTokens, int completionTokens, BigDecimal cost) {
        LocalDate today = LocalDate.now();
        long total = (long) promptTokens + completionTokens;
        if (repository.accumulate(userId, model, today, promptTokens, completionTokens, total, cost, Instant.now()) > 0) {
            return;
        }
        try {
            rowCreator.create(userId, model, today, promptTokens, completionTokens, cost, calculator.currency());
        } catch (DataIntegrityViolationException raced) {
            repository.accumulate(userId, model, today, promptTokens, completionTokens, total, cost, Instant.now());
        }
    }
}
