package com.maibao.tech.usage;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;


/**
 * 成本在 Redis 里的存取。
 *
 * <p>高频写只打这里：一次调用一次 pipeline（7 条命令、一次往返），
 * 数据库由 {@code CostFlushJob} 每分钟覆盖写一次。</p>
 *
 * <p>键的拼装在 {@link CostKeys}，金额换算在 {@link Amounts}，
 * 这个类只负责"怎么读写"。</p>
 */
@Component
public class CostRedisRepository {

    private final StringRedisTemplate redis;
    private final CostKeys keys;
    private final Duration ttl;

    public CostRedisRepository(StringRedisTemplate redis, CostProperties props) {
        this.redis = redis;
        this.keys = new CostKeys(props.getRedis().getKeyPrefix());
        this.ttl = Duration.ofDays(props.getRedis().getTtlDays());
    }

    /**
     * 累加一次调用：4 个指标 + 用户维度汇总。
     *
     * <p>用 pipeline 而不是逐条命令，把 7 次往返压成 1 次；对原子性要求更高时
     * 可以把这段换成 Lua 脚本。</p>
     */
    @SuppressWarnings("unchecked")
    public void increase(String userId, String model, LocalDate day,
                         int promptTokens, int completionTokens, BigDecimal cost) {
        String accKey = keys.acc(day);
        String userKey = keys.user(day);
        long costScaled = toScaled(cost);

        redis.executePipelined(new SessionCallback<Object>() {
            @Override
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                ops.opsForHash().increment(accKey, CostKeys.field(userId, model, CostKeys.Metric.COST), costScaled);
                ops.opsForHash().increment(accKey, CostKeys.field(userId, model, CostKeys.Metric.PROMPT_TOKENS), promptTokens);
                ops.opsForHash().increment(accKey, CostKeys.field(userId, model, CostKeys.Metric.COMPLETION_TOKENS), completionTokens);
                ops.opsForHash().increment(accKey, CostKeys.field(userId, model, CostKeys.Metric.COUNT), 1);
                ops.opsForHash().increment(userKey, userId, costScaled);
                ops.expire(accKey, ttl);
                ops.expire(userKey, ttl);
                return null;
            }
        });
    }

    /** 某用户当天已用金额（元）。配额校验读这个，O(1)。 */
    public BigDecimal userCost(String userId, LocalDate day) {
        Object value = redis.opsForHash().get(keys.user(day), userId);
        return BigDecimal.valueOf(value == null ? 0L : parseLong(value), CostAggregate.SCALE);
    }

    /** 当天全量快照，已按 {@code userId|model} 聚合好，落盘直接用。 */
    public List<CostAggregate> aggregate(LocalDate day) {
        Map<Object, Object> raw = redis.opsForHash().entries(keys.acc(day));
        Map<String, MutableAggregate> grouped = new HashMap<>();
        for (Map.Entry<Object, Object> entry : raw.entrySet()) {
            String[] parts = CostKeys.splitField(String.valueOf(entry.getKey()));
            CostKeys.Metric metric = parts == null ? null : CostKeys.Metric.of(parts[2]);
            if (metric == null) {
                continue;
            }
            MutableAggregate agg = grouped.computeIfAbsent(parts[0] + "|" + parts[1],
                    k -> new MutableAggregate(parts[0], parts[1]));
            agg.add(metric, parseLong(entry.getValue()));
        }
        List<CostAggregate> result = new ArrayList<>(grouped.size());
        for (MutableAggregate agg : grouped.values()) {
            result.add(agg.toRecord());
        }
        return result;
    }

    /** 当天总花费（元）。对账用。 */
    public BigDecimal dayTotal(LocalDate day) {
        long sum = 0L;
        for (CostAggregate agg : aggregate(day)) {
            sum += agg.costScaled();
        }
        return BigDecimal.valueOf(sum, CostAggregate.SCALE);
    }

    /** 日结：整组键删掉，不用 SCAN。 */
    public void deleteDay(LocalDate day) {
        redis.delete(List.of(keys.acc(day), keys.user(day)));
    }

    private static long toScaled(BigDecimal yuan) {
        if (yuan == null) {
            return 0L;
        }
        return yuan.multiply(BigDecimal.TEN.pow(CostAggregate.SCALE))
                .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
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

    /** 聚合过程中的可变累加器，只在 {@link #aggregate} 内部用。 */
    private static final class MutableAggregate {

        private final String userId;
        private final String model;
        private long cost;
        private long promptTokens;
        private long completionTokens;
        private long count;

        private MutableAggregate(String userId, String model) {
            this.userId = userId;
            this.model = model;
        }

        private void add(CostKeys.Metric metric, long value) {
            switch (metric) {
                case COST -> cost += value;
                case PROMPT_TOKENS -> promptTokens += value;
                case COMPLETION_TOKENS -> completionTokens += value;
                case COUNT -> count += value;
            }
        }

        private CostAggregate toRecord() {
            return new CostAggregate(userId, model, cost, promptTokens, completionTokens, count);
        }
    }
}
