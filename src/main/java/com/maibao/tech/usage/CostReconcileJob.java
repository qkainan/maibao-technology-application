package com.maibao.tech.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


import io.micrometer.core.instrument.MeterRegistry;
import com.maibao.tech.usage.util.CostProperties;

/**
 * 成本对账：Redis 当日累计 vs 数据库聚合。
 *
 * <p>两者天然差一个落盘周期（默认 60s），所以判据不是"必须相等"，
 * 而是"差异是否超过一个周期可能产生的量"。</p>
 */
@Component
public class CostReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(CostReconcileJob.class);

    private static final BigDecimal ABS_THRESHOLD = new BigDecimal("1.00");
    private static final BigDecimal REL_THRESHOLD = new BigDecimal("0.05");

    private final CostRedisRepository costRedis;
    private final JdbcTemplate jdbc;
    private final CostProperties properties;
    private final MeterRegistry meterRegistry;
    private final ZoneId zone;

    public CostReconcileJob(CostRedisRepository costRedis, JdbcTemplate jdbc,
                            CostProperties properties, MeterRegistry meterRegistry) {
        this.costRedis = costRedis;
        this.jdbc = jdbc;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.zone = ZoneId.of(properties.getZone());
    }

    @Scheduled(fixedDelayString = "${app.cost.reconcile-ms:300000}", initialDelay = 120000)
    public void reconcileToday() {
        LocalDate today = LocalDate.now(zone);
        BigDecimal redisTotal = costRedis.dayTotal(today);
        BigDecimal dbTotal = jdbc.queryForObject(
                "SELECT COALESCE(SUM(cost_amount), 0) FROM cost_accounting WHERE stat_date = ?",
                BigDecimal.class, Date.valueOf(today));
        if (dbTotal == null) {
            dbTotal = BigDecimal.ZERO;
        }

        BigDecimal diff = redisTotal.subtract(dbTotal).abs();
        boolean overThreshold = diff.compareTo(ABS_THRESHOLD) > 0
                && (redisTotal.signum() <= 0
                    || diff.divide(redisTotal, 4, RoundingMode.HALF_UP).compareTo(REL_THRESHOLD) > 0);
        if (overThreshold) {
            log.error("cost reconcile lag too large: date={} redis={} db={} diff={}",
                    today, redisTotal, dbTotal, diff);
            meterRegistry.counter("cost.reconcile.lag").increment();
        } else {
            log.debug("cost reconcile ok: date={} redis={} db={} diff={}", today, redisTotal, dbTotal, diff);
        }
    }
}
