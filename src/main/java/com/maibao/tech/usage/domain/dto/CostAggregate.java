package com.maibao.tech.usage.domain.dto;

import java.math.BigDecimal;

import com.maibao.tech.usage.util.Amounts;

/**
 * 一次落盘的单位：某用户、某模型、某天的累计。
 *
 * <p>替掉原来的 {@code long[]} —— {@code agg[0]} 取金额、{@code agg[3]} 取次数这种写法，
 * 读代码的人得回头数下标。</p>
 */
public record CostAggregate(String userId,
                            String model,
                            long costScaled,
                            long promptTokens,
                            long completionTokens,
                            long count) {

    public BigDecimal costYuan() {
        return Amounts.toYuan(costScaled);
    }

    public long totalTokens() {
        return promptTokens + completionTokens;
    }
}
