package com.maibao.tech.usage;

import com.maibao.tech.usage.util.CostCalculator;
import com.maibao.tech.usage.util.TokenEstimator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.maibao.tech.usage.domain.CostAccounting;
import com.maibao.tech.usage.domain.CostView;
import com.maibao.tech.usage.domain.QueryLogView;
import com.maibao.tech.usage.domain.UsageSummaryView;
import com.maibao.tech.usage.domain.UserQueryLog;
import com.maibao.tech.usage.repository.CostAccountingRepository;
import com.maibao.tech.usage.repository.UserQueryLogRepository;

/**
 * 对话埋点：明细进本地批量队列，成本只累加到 Redis。
 *
 * <p>两件事都不碰数据库的热路径：</p>
 * <ul>
 *   <li>明细：{@link DetailBuffer} 每 200ms 批量 INSERT（IDENTITY 主键下 JPA 批量会失效，所以走 JdbcTemplate）；</li>
 *   <li>成本：{@link CostAccumulator} 一次 Redis pipeline，{@link CostFlushJob} 每分钟覆盖写落库。</li>
 * </ul>
 *
 * <p>埋点是旁路：任何一步失败都只告警，不影响对话本身。</p>
 */
@Service
public class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);

    private static final int MAX_PROMPT = 2000;
    private static final int MAX_REPLY = 4000;
    private static final int MAX_ERROR = 255;
    private static final String ANONYMOUS = "anonymous";

    private final UserQueryLogRepository queryLogRepository;
    private final CostAccountingRepository costRepository;
    private final TokenEstimator estimator;
    private final CostCalculator calculator;
    private final CostAccumulator accumulator;
    private final DetailBuffer detailBuffer;
    private final ZoneId zone;

    public UsageService(UserQueryLogRepository queryLogRepository,
                        CostAccountingRepository costRepository,
                        TokenEstimator estimator,
                        CostCalculator calculator,
                        CostAccumulator accumulator,
                        DetailBuffer detailBuffer,
                        @Value("${app.cost.zone:Asia/Shanghai}") String zone) {
        this.queryLogRepository = queryLogRepository;
        this.costRepository = costRepository;
        this.estimator = estimator;
        this.calculator = calculator;
        this.accumulator = accumulator;
        this.detailBuffer = detailBuffer;
        this.zone = ZoneId.of(zone);
    }

    public void record(String userId, String model, String source, String prompt, String reply,
                       long latencyMs, String status, String error) {
        String uid = normalize(userId);
        int promptTokens = estimator.estimate(prompt);
        int completionTokens = estimator.estimate(reply);
        boolean billable = "SUCCESS".equals(status);
        BigDecimal cost = billable
                ? calculator.amount(model, promptTokens, completionTokens)
                : BigDecimal.ZERO;

        UserQueryLog row = new UserQueryLog(UUID.randomUUID().toString(), uid, model, source,
                truncate(prompt, MAX_PROMPT), truncate(reply, MAX_REPLY),
                promptTokens, completionTokens, cost,
                (int) Math.min(Integer.MAX_VALUE, Math.max(0, latencyMs)),
                status, truncate(error, MAX_ERROR));

        // ① 明细：只入本地队列，不做 IO
        detailBuffer.offer(row);

        if (!billable) {
            return;
        }

        // ② 成本：一次 Redis pipeline；失败只告警（聚合表由落盘 Job 兜底）
        try {
            accumulator.accumulate(uid, model, LocalDate.now(zone), promptTokens, completionTokens, cost);
        } catch (RuntimeException e) {
            log.warn("cost accumulate failed user={} model={}: {}", uid, model, e.toString());
        }
    }

    public List<QueryLogView> recentQueries(String userId, int limit) {
        int size = Math.max(1, Math.min(limit, 200));
        Pageable page = PageRequest.of(0, size);
        List<UserQueryLog> rows = (userId == null || userId.isBlank())
                ? queryLogRepository.findAllByOrderByIdDesc(page)
                : queryLogRepository.findByUserIdOrderByIdDesc(userId.trim(), page);
        return rows.stream().map(QueryLogView::of).toList();
    }

    public List<CostView> costByModel(String userId) {
        return costRepository.findByUserIdOrderByStatDateDescModelAsc(normalize(userId))
                .stream().map(CostView::of).toList();
    }

    public UsageSummaryView summary(String userId) {
        String uid = normalize(userId);
        List<CostAccounting> rows = costRepository.findByUserIdOrderByStatDateDescModelAsc(uid);
        long requests = rows.stream().mapToLong(CostAccounting::getRequestCount).sum();
        long tokens = rows.stream().mapToLong(CostAccounting::getTotalTokens).sum();
        BigDecimal cost = rows.stream().map(CostAccounting::getCostAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new UsageSummaryView(uid, requests, tokens, cost, calculator.currency());
    }

    private String normalize(String userId) {
        return (userId == null || userId.isBlank()) ? ANONYMOUS : userId.trim();
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() > max ? text.substring(0, max) : text;
    }
}
