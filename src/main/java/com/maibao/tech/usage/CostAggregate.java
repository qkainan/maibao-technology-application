package com.maibao.tech.usage;

import java.math.BigDecimal;

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

    /** 金额在 Redis 里保留的小数位数（定点整数，避免浮点漂移） */
    public static final int SCALE = 8;

    public BigDecimal costYuan() {
        return BigDecimal.valueOf(costScaled, SCALE);
    }

    public long totalTokens() {
        return promptTokens + completionTokens;
    }
}
