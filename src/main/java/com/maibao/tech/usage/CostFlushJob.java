package com.maibao.tech.usage;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


/**
 * 成本落盘：把 Redis 的当日累计<b>覆盖写</b>到 {@code cost_accounting}。
 *
 * <p>为什么是覆盖写而不是累加：Redis 里存的就是"当日截至目前的累计值"，落盘只是同步。
 * {@code cost_amount = VALUES(cost_amount)} 让重跑任意多次结果一致 ——
 * <b>幂等靠覆盖状态，不需要去重表</b>。</p>
 *
 * <p>日结（默认 00:05）：最后落一次盘 → 删当日 Redis 键 → 清理过期明细。</p>
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

    private final CostRedisRepository costRedis;
    private final JdbcTemplate jdbc;
    private final CostProperties properties;
    private final ZoneId zone;

    public CostFlushJob(CostRedisRepository costRedis, JdbcTemplate jdbc, CostProperties properties) {
        this.costRedis = costRedis;
        this.jdbc = jdbc;
        this.properties = properties;
        this.zone = ZoneId.of(properties.getZone());
    }

    @Scheduled(fixedDelayString = "${app.cost.flush-ms:60000}", initialDelay = 30000)
    public void flushToday() {
        flush(LocalDate.now(zone));
    }

    /** @return 落盘的行数（活跃的 user × model 组合数） */
    public int flush(LocalDate day) {
        List<CostAggregate> snapshot = costRedis.aggregate(day);
        if (snapshot.isEmpty()) {
            return 0;
        }
        String currency = properties.getCurrency();
        Timestamp now = Timestamp.from(Instant.now());
        Date statDate = Date.valueOf(day);
        List<Object[]> args = new ArrayList<>(snapshot.size());
        for (CostAggregate agg : snapshot) {
            long totalTokens = agg.totalTokens();
            BigDecimal cost = agg.costYuan();
            args.add(new Object[]{
                    agg.userId(), agg.model(), statDate, agg.count(),
                    agg.promptTokens(), agg.completionTokens(), totalTokens,
                    cost, currency, now});
        }
        jdbc.batchUpdate(UPSERT_SQL, args);
        log.info("cost flushed: day={} rows={}", day, args.size());
        return args.size();
    }

    @Scheduled(cron = "${app.cost.settle-cron:0 5 0 * * *}")
    public void settleYesterday() {
        LocalDate yesterday = LocalDate.now(zone).minusDays(1);
        int rows = flush(yesterday);
        costRedis.deleteDay(yesterday);
        int cleaned = cleanupDetails(yesterday.minusDays(properties.getDetail().getRetainDays()));
        log.info("cost settled: day={} rows={} redisCleared=true detailCleaned={}",
                yesterday, rows, cleaned);
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
