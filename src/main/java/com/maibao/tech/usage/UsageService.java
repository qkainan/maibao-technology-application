package com.maibao.tech.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.maibao.tech.usage.CostProperties;
import com.maibao.tech.usage.detail.DetailBuffer;
import com.maibao.tech.usage.detail.DetailRecord;
import com.maibao.tech.usage.domain.CostAccounting;
import com.maibao.tech.usage.domain.CostView;
import com.maibao.tech.usage.domain.QueryLogView;
import com.maibao.tech.usage.domain.UsageSummaryView;
import com.maibao.tech.usage.domain.UserQueryLog;
import com.maibao.tech.usage.CostRedisRepository;
import com.maibao.tech.usage.repository.CostAccountingRepository;
import com.maibao.tech.usage.repository.UserQueryLogRepository;

/**
 * 对话埋点：明细入本地缓冲、成本写 Redis —— 两件事都不在请求线程碰热路径。
 *
 * <ul>
 *   <li>明细：{@link DetailBuffer} 每 200ms 攒一批，走 MQ 或直写库里；</li>
 *   <li>成本：一次 Redis pipeline 累加，{@code CostFlushJob} 每分钟覆盖写落库。</li>
 * </ul>
 *
 * <p>埋点是旁路：任一步失败都只告警，不影响对话本身。</p>
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
    private final CostRedisRepository costRedis;
    private final DetailBuffer detailBuffer;
    private final ZoneId zone;

    public UsageService(UserQueryLogRepository queryLogRepository,
                        CostAccountingRepository costRepository,
                        TokenEstimator estimator,
                        CostCalculator calculator,
                        CostRedisRepository costRedis,
                        DetailBuffer detailBuffer,
                        CostProperties properties) {
        this.queryLogRepository = queryLogRepository;
        this.costRepository = costRepository;
        this.estimator = estimator;
        this.calculator = calculator;
        this.costRedis = costRedis;
        this.detailBuffer = detailBuffer;
        this.zone = ZoneId.of(properties.getZone());
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

        DetailRecord detail = new DetailRecord(UUID.randomUUID().toString(), uid, model, source,
                truncate(prompt, MAX_PROMPT), truncate(reply, MAX_REPLY),
                promptTokens, completionTokens, cost,
                (int) Math.min(Integer.MAX_VALUE, Math.max(0, latencyMs)),
                status, truncate(error, MAX_ERROR), Instant.now());

        detailBuffer.offer(detail);

        if (!billable) {
            return;
        }
        try {
            costRedis.increase(uid, model, LocalDate.now(zone), promptTokens, completionTokens, cost);
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
