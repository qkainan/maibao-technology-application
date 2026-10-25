package com.maibao.tech.usage.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额折算：元 ↔ Redis 里的定点整数。
 *
 * <p>Redis 的 {@code HINCRBY} 只能自增整数，所以金额按 {@code 元 × 1e8} 存 long，
 * 累加不会有浮点漂移。</p>
 */
public final class Amounts {

    /** Redis 里保留的小数位数 */
    public static final int SCALE = 8;

    private static final BigDecimal FACTOR = BigDecimal.TEN.pow(SCALE);

    private Amounts() {
    }

    /** 元 → Redis 整数 */
    public static long toScaled(BigDecimal yuan) {
        if (yuan == null) {
            return 0L;
        }
        return yuan.multiply(FACTOR).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** Redis 整数 → 元 */
    public static BigDecimal toYuan(long scaled) {
        return BigDecimal.valueOf(scaled, SCALE);
    }
}
