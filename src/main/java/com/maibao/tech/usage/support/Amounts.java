package com.maibao.tech.usage.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额与 Redis 整数之间的换算。
 *
 * <p>Redis 里统一存 {@code 元 × 1e8} 的 long：{@code HINCRBY} 只能自增整数，
 * 用定点数可以避免浮点累加漂移（{@code 0.1 + 0.2} 那类问题）。</p>
 */
public final class Amounts {

    /** Redis 里保留的小数位数 */
    public static final int SCALE = 8;

    private static final BigDecimal FACTOR = BigDecimal.TEN.pow(SCALE);

    private Amounts() {
    }

    /** 元 → Redis 里的整数（四舍五入到 1e-8） */
    public static long toScaled(BigDecimal yuan) {
        if (yuan == null) {
            return 0L;
        }
        return yuan.multiply(FACTOR).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** Redis 里的整数 → 元 */
    public static BigDecimal toYuan(long scaled) {
        return BigDecimal.valueOf(scaled, SCALE);
    }
}
