package com.maibao.tech.usage.redis;

/**
 * 一次落盘的单位：某用户、某模型、某天的累计。
 *
 * <p>存在的意义是替掉原来的 {@code long[]} —— 用 {@code agg[0]} 取金额、
 * {@code agg[3]} 取次数这种写法，读代码的人得回头数下标。</p>
 */
public record CostAggregate(String userId, String model,
                            long costScaled, long promptTokens, long completionTokens, long count) {
}
