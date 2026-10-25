package com.maibao.tech.usage.support;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 成本相关 Redis 键的拼装。
 *
 * <p>按天分桶：一个日期一组键，日结时整组 {@code DEL} 即可，
 * 不用 SCAN 找历史键。</p>
 *
 * <pre>
 * {prefix}:acc:20261025   Hash   field = userId|model|metric   → 落盘用
 * {prefix}:user:20261025  Hash   field = userId                → 配额用（O(1) 读）
 * </pre>
 */
public final class CostKeys {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final char SEP = '|';

    private final String prefix;

    public CostKeys(String prefix) {
        this.prefix = prefix;
    }

    /** 用户 × 模型 × 指标 的累加表 */
    public String acc(LocalDate day) {
        return prefix + ":acc:" + DAY.format(day);
    }

    /** 用户当日已用金额（配额校验读这个） */
    public String user(LocalDate day) {
        return prefix + ":user:" + DAY.format(day);
    }

    /** acc 表的字段名 */
    public static String field(String userId, String model, Metric metric) {
        return userId + SEP + model + SEP + metric.code;
    }

    /**
     * 反解 acc 字段名。
     *
     * @return {@code [userId, model, metric]}，格式不对返回 {@code null}
     */
    public static String[] splitField(String field) {
        int metricAt = field.lastIndexOf(SEP);
        if (metricAt <= 0) {
            return null;
        }
        String owner = field.substring(0, metricAt);
        int ownerAt = owner.indexOf(SEP);
        if (ownerAt <= 0 || ownerAt == owner.length() - 1) {
            return null;
        }
        return new String[]{owner.substring(0, ownerAt), owner.substring(ownerAt + 1),
                field.substring(metricAt + 1)};
    }

    /** acc 表里记的四个指标 */
    public enum Metric {

        COST("cost"),
        PROMPT_TOKENS("pt"),
        COMPLETION_TOKENS("ct"),
        COUNT("cnt");

        private final String code;

        Metric(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Metric of(String code) {
            for (Metric metric : values()) {
                if (metric.code.equals(code)) {
                    return metric;
                }
            }
            return null;
        }
    }
}
