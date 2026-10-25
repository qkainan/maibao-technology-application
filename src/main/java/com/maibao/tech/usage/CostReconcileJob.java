package com.maibao.tech.usage;

import com.maibao.tech.usage.util.CostProperties;
import com.maibao.tech.usage.util.CostRedisStore;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 成本对账：Redis 当日累计 vs DB 聚合表。
 *
 * <p>两者天然有差异 —— Redis 领先 DB 一个落盘周期（默认 60s），以及最后一段时间还没落盘。
 * 所以判据是：差异是否超过"一个落盘周期内可能产生的量"，超过才告警。</p>
 */
@Component
public class CostReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(CostReconcileJob.class);

    private static final BigDecimal ABS_THRESHOLD = new BigDecimal("1.00");
    private static final BigDecimal REL_THRESHOLD = new BigDecimal("0.05");

    private final CostRedisStore store;
    private final JdbcTemplate jdbc;
    private final CostProperties props;
    private final MeterRegistry meterRegistry;

    public CostReconcileJob(CostRedisStore store, JdbcTemplate jdbc,
                            CostProperties props, MeterRegistry meterRegistry) {
        this.store = store;
        this.jdbc = jdbc;
        this.props = props;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${app.cost.reconcile-ms:300000}", initialDelay = 120000)
    public void reconcileToday() {
        LocalDate today = LocalDate.now(props.zoneId());
        BigDecimal redis = store.dayTotal(today);
        BigDecimal db = jdbc.queryForObject(
                "SELECT COALESCE(SUM(cost_amount), 0) FROM cost_accounting WHERE stat_date = ?",
                BigDecimal.class, java.sql.Date.valueOf(today));

        BigDecimal diff = redis.subtract(db).abs();
        boolean over = diff.compareTo(ABS_THRESHOLD) > 0
                && (redis.signum() <= 0
                    || diff.divide(redis, 4, java.math.RoundingMode.HALF_UP).compareTo(REL_THRESHOLD) > 0);
        if (over) {
            log.error("cost reconcile lag too large: date={} redis={} db={} diff={}", today, redis, db, diff);
            meterRegistry.counter("cost.reconcile.lag").increment();
        } else {
            log.debug("cost reconcile ok: date={} redis={} db={} diff={}", today, redis, db, diff);
        }
    }
}
