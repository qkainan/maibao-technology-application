package com.maibao.tech.usage.util;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.stereotype.Service;

/**
 * 成本累加：<b>只写 Redis</b>，不碰数据库。
 *
 * <p>1000 QPS 下如果每条消息都走 DB 累加（{@code UPDATE ... += ?}），
 * 单表行锁 + 6 次状态写会直接把库打满。这里改成一次 pipeline，
 * DB 由 {@link CostFlushJob} 每分钟覆盖写一次。</p>
 */
@Service
public class CostAccumulator {

    private final CostRedisStore store;

    public CostAccumulator(CostRedisStore store) {
        this.store = store;
    }

    public void accumulate(String userId, String model, LocalDate statDate,
                           int promptTokens, int completionTokens, BigDecimal cost) {
        store.increase(userId, model, statDate, promptTokens, completionTokens, cost);
    }
}
