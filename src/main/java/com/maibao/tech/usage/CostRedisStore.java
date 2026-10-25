package com.maibao.tech.usage;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 成本累加的 Redis 层：高频写只打 Redis，DB 只做分钟级落盘。
 *
 * <p>两个 Hash（都按天分桶，TTL 兜底）：</p>
 * <ul>
 *   <li>{@code {prefix}:acc:{yyyyMMdd}} —— field {@code userId|model|cost|pt|ct|cnt}，
 *       给落盘 Job 用（按用户×模型聚合）；</li>
 *   <li>{@code {prefix}:user:{yyyyMMdd}} —— field {@code userId}，给配额校验用（O(1) 读）。</li>
 * </ul>
 *
 * <p>金额统一用 {@code long × 1e8} 存整数，避免浮点累加漂移。</p>
 *
 * <p>写路径用 pipeline（一次往返），生产如需严格原子可换 Lua 脚本。</p>
 */
@Component
public class CostRedisStore {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final BigDecimal L1E8 = BigDecimal.valueOf(100_000_000L);

    private final StringRedisTemplate redis;
    private final String prefix;
    private final int ttlDays;

    public CostRedisStore(StringRedisTemplate redis, CostProperties props) {
        this.redis = redis;
        this.prefix = props.getRedis().getKeyPrefix();
        this.ttlDays = props.getRedis().getTtlDays();
    }

    /** 一次调用：累加 4 个指标 + 用户维度汇总。pipeline 一次往返。 */
    @SuppressWarnings("unchecked")
    public void increase(String userId, String model, LocalDate day,
                         int promptTokens, int completionTokens, BigDecimal cost) {
        String accKey = accKey(day);
        String userKey = userKey(day);
        String field = userId + "|" + model;
        long costL1e8 = toL1e8(cost);
        Duration ttl = Duration.ofDays(ttlDays);

        redis.executePipelined(new SessionCallback<Object>() {
            @Override
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                ops.opsForHash().increment(accKey, field + "|cost", costL1e8);
                ops.opsForHash().increment(accKey, field + "|pt", promptTokens);
                ops.opsForHash().increment(accKey, field + "|ct", completionTokens);
                ops.opsForHash().increment(accKey, field + "|cnt", 1);
                ops.opsForHash().increment(userKey, userId, costL1e8);
                ops.expire(accKey, ttl);
                ops.expire(userKey, ttl);
                return null;
            }
        });
    }

    /** 某用户当天已用金额（元）。配额校验读这个。 */
    public BigDecimal userCost(String userId, LocalDate day) {
        Object value = redis.opsForHash().get(userKey(day), userId);
        return fromL1e8(value == null ? 0L : parseLong(value));
    }

    /**
     * 当天全量快照，已按 {@code userId|model} 聚合。
     *
     * @return key = userId|model，value = [costL1e8, promptTokens, completionTokens, count]
     */
    public Map<String, long[]> aggregate(LocalDate day) {
        Map<Object, Object> raw = redis.opsForHash().entries(accKey(day));
        Map<String, long[]> result = new HashMap<>();
        for (Map.Entry<Object, Object> entry : raw.entrySet()) {
            String key = String.valueOf(entry.getKey());
            int idx = key.lastIndexOf('|');
            if (idx <= 0) {
                continue;
            }
            String owner = key.substring(0, idx);
            String metric = key.substring(idx + 1);
            long value = parseLong(entry.getValue());
            long[] agg = result.computeIfAbsent(owner, k -> new long[4]);
            switch (metric) {
                case "cost" -> agg[0] += value;
                case "pt" -> agg[1] += value;
                case "ct" -> agg[2] += value;
                case "cnt" -> agg[3] += value;
                default -> { }
            }
        }
        return result;
    }

    public BigDecimal dayTotal(LocalDate day) {
        long sum = 0L;
        for (long[] agg : aggregate(day).values()) {
            sum += agg[0];
        }
        return fromL1e8(sum);
    }

    public void deleteDay(LocalDate day) {
        redis.delete(List.of(accKey(day), userKey(day)));
    }

    public String accKey(LocalDate day) {
        return prefix + ":acc:" + DAY.format(day);
    }

    public String userKey(LocalDate day) {
        return prefix + ":user:" + DAY.format(day);
    }

    public static long toL1e8(BigDecimal cost) {
        if (cost == null) {
            return 0L;
        }
        return cost.multiply(L1E8).setScale(0, java.math.RoundingMode.HALF_UP).longValue();
    }

    public static BigDecimal fromL1e8(long value) {
        return BigDecimal.valueOf(value, 8);
    }

    private static long parseLong(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
