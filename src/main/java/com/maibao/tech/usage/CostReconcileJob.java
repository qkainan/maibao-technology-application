package com.maibao.tech.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.maibao.tech.mq.domain.OutboxMessage;
import com.maibao.tech.mq.repository.OutboxRepository;
import com.maibao.tech.usage.repository.CostAccountingRepository;
import com.maibao.tech.usage.repository.CostMessageLogRepository;
import com.maibao.tech.usage.repository.UserQueryLogRepository;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 成本对账与去重表清理。
 *
 * <p>三条铁律：只对 T-1（当天还在滚动，必然对不上）；阈值不为 0（绝对 0.01 元或相对 0.5%）；
 * 只发现不自动修（自动修复错了更难查，告警后人工确认再重放）。</p>
 */
@Component
public class CostReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(CostReconcileJob.class);
    private static final BigDecimal ABS_THRESHOLD = new BigDecimal("0.01");
    private static final BigDecimal REL_THRESHOLD = new BigDecimal("0.005");

    private final UserQueryLogRepository queryLogRepository;
    private final CostAccountingRepository costRepository;
    private final CostMessageLogRepository messageLogRepository;
    private final OutboxRepository outboxRepository;
    private final MeterRegistry meterRegistry;
    private final ZoneId zone;
    private final int retainDays;

    public CostReconcileJob(UserQueryLogRepository queryLogRepository,
                            CostAccountingRepository costRepository,
                            CostMessageLogRepository messageLogRepository,
                            OutboxRepository outboxRepository,
                            MeterRegistry meterRegistry,
                            @Value("${app.cost.zone:Asia/Shanghai}") String zone,
                            @Value("${app.cost.dedupe-retain-days:7}") int retainDays) {
        this.queryLogRepository = queryLogRepository;
        this.costRepository = costRepository;
        this.messageLogRepository = messageLogRepository;
        this.outboxRepository = outboxRepository;
        this.meterRegistry = meterRegistry;
        this.zone = ZoneId.of(zone);
        this.retainDays = retainDays;
    }

    @Scheduled(cron = "${app.cost.reconcile-cron:0 30 2 * * *}", zone = "Asia/Shanghai")
    public void reconcile() {
        LocalDate day = LocalDate.now(zone).minusDays(1);
        Instant from = day.atStartOfDay(zone).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();

        BigDecimal detail = queryLogRepository.sumSuccessCost(from, to);
        BigDecimal aggregate = costRepository.sumByStatDate(day);
        checkMoney(day, detail, aggregate);

        long detailCount = queryLogRepository.countSuccess(from, to);
        long messageCount = messageLogRepository.countByCreatedAtBetween(from, to);
        if (detailCount != messageCount) {
            log.error("cost reconcile: detail count {} != cost message count {} on {}", detailCount, messageCount, day);
            meterRegistry.counter("cost.reconcile.mismatch", "type", "count").increment();
        } else {
            log.info("cost reconcile: {} detail rows == {} cost messages", detailCount, messageCount);
        }

        long failed = outboxRepository.countByStatus(OutboxMessage.FAILED);
        long stuck = outboxRepository.countByStatusAndAttemptsAtLeast(OutboxMessage.PENDING, 3);
        if (failed > 0 || stuck > 0) {
            log.warn("outbox health: failed={} stuckPending={}", failed, stuck);
            meterRegistry.counter("cost.reconcile.mismatch", "type", "outbox").increment();
        }
    }

    @Scheduled(cron = "${app.cost.cleanup-cron:0 0 3 * * *}", zone = "Asia/Shanghai")
    public void cleanupDedupeLog() {
        Instant threshold = Instant.now().minus(Duration.ofDays(retainDays));
        int deleted = messageLogRepository.deleteOlderThan(threshold);
        if (deleted > 0) {
            log.info("cost message log cleanup: deleted {} rows older than {} days", deleted, retainDays);
        }
    }

    private void checkMoney(LocalDate day, BigDecimal detail, BigDecimal aggregate) {
        BigDecimal diff = detail.subtract(aggregate).abs();
        BigDecimal base = detail.abs();
        boolean overThreshold = diff.compareTo(ABS_THRESHOLD) > 0
                || (base.signum() > 0
                    && diff.divide(base, 4, RoundingMode.HALF_UP).compareTo(REL_THRESHOLD) > 0);
        if (overThreshold) {
            log.error("cost reconcile mismatch on {}: detail={} aggregate={} diff={}",
                    day, detail, aggregate, diff);
            meterRegistry.counter("cost.reconcile.mismatch", "type", "amount").increment();
        } else {
            log.info("cost reconcile ok on {}: detail={} aggregate={}", day, detail, aggregate);
        }
    }
}
