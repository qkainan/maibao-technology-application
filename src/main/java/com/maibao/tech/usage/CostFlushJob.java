package com.maibao.tech.usage;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 成本落盘：把 Redis 的当日累计<b>覆盖写</b>到 {@code cost_accounting}。
 *
 * <p><b>为什么是覆盖写而不是累加</b>：Redis 里存的就是"当日截至目前的累计值"，
 * 落盘只是把它同步到 DB。用 {@code cost_amount = VALUES(cost_amount)} 覆盖，
 * 重跑任意多次结果都一样 —— <b>幂等不需要去重表</b>。</p>
 *
 * <p>日结（默认 00:05）：最后落一次盘 → 删当日 Redis key → 清理过期明细。</p>
 */
@Component
public class CostFlushJob {

    private static final Logger log = LoggerFactory.getLogger(CostFlushJob.class);

    private static final String UPSERT_SQL = """
            INSERT INTO cost_accounting
                (user_id, model, stat_date, request_count, prompt_tokens, completion_tokens,
                 total_tokens, cost_amount, currency, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE
                request_count = VALUES(request_count),
                prompt_tokens = VALUES(prompt_tokens),
                completion_tokens = VALUES(completion_tokens),
                total_tokens = VALUES(total_tokens),
                cost_amount = VALUES(cost_amount),
                currency = VALUES(currency),
                updated_at = VALUES(updated_at)
            """;

    private final CostRedisStore store;
    private final JdbcTemplate jdbc;
    private final CostProperties props;

    public CostFlushJob(CostRedisStore store, JdbcTemplate jdbc, CostProperties props) {
        this.store = store;
        this.jdbc = jdbc;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${app.cost.flush-ms:60000}", initialDelay = 30000)
    public void flushToday() {
        flush(LocalDate.now(props.zoneId()));
    }

    /** @return 落盘的行数（活跃的 user×model 组合数） */
    public int flush(LocalDate day) {
        Map<String, long[]> snapshot = store.aggregate(day);
        if (snapshot.isEmpty()) {
            return 0;
        }
        String currency = props.getCurrency();
        Timestamp now = Timestamp.from(Instant.now());
        List<Object[]> args = new ArrayList<>(snapshot.size());
        for (Map.Entry<String, long[]> entry : snapshot.entrySet()) {
            String owner = entry.getKey();
            int idx = owner.indexOf('|');
            if (idx <= 0 || idx == owner.length() - 1) {
                continue;
            }
            String userId = owner.substring(0, idx);
            String model = owner.substring(idx + 1);
            long[] agg = entry.getValue();
            long promptTokens = agg[1];
            long completionTokens = agg[2];
            long count = Math.max(agg[3], 0L);
            BigDecimal cost = CostRedisStore.fromL1e8(agg[0]);
            args.add(new Object[]{
                    userId, model, Date.valueOf(day), count,
                    promptTokens, completionTokens, promptTokens + completionTokens,
                    cost, currency, now});
        }
        if (args.isEmpty()) {
            return 0;
        }
        jdbc.batchUpdate(UPSERT_SQL, args);
        log.info("cost flushed: day={} rows={}", day, args.size());
        return args.size();
    }

    @Scheduled(cron = "${app.cost.settle-cron:0 5 0 * * *}")
    public void settleYesterday() {
        LocalDate yesterday = LocalDate.now(props.zoneId()).minusDays(1);
        int rows = flush(yesterday);
        store.deleteDay(yesterday);
        int cleaned = cleanupDetails(yesterday.minusDays(props.getDetail().getRetainDays()));
        log.info("cost settled: day={} rows={} redisCleared=true detailCleaned={}", yesterday, rows, cleaned);
    }

    /** 明细只保留热数据；生产应换成按天分区 + DROP PARTITION。 */
    public int cleanupDetails(LocalDate before) {
        if (before == null) {
            return 0;
        }
        return jdbc.update("DELETE FROM user_query_log WHERE created_at < ?",
                Timestamp.valueOf(before.atStartOfDay()));
    }
}
