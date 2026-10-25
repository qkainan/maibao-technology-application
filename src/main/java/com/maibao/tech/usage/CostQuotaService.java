package com.maibao.tech.usage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.maibao.tech.usage.config.CostProperties;
import com.maibao.tech.usage.domain.QuotaView;
import com.maibao.tech.usage.redis.CostRedisRepository;

/**
 * 用户配额：调用模型<b>之前</b>校验，超限直接拒绝，不产生任何模型调用与费用。
 *
 * <p>数据源是 Redis 的当日累计（O(1) 读），所以放在请求路径上也不会成为瓶颈。
 * Redis 不可用时<b>放行</b>：配额是成本控制，不是安全边界，
 * 不能因为缓存故障把业务全阻断。</p>
 */
@Service
public class CostQuotaService {

    private static final Logger log = LoggerFactory.getLogger(CostQuotaService.class);

    private final CostRedisRepository costRedis;
    private final CostProperties properties;
    private final ZoneId zone;

    public CostQuotaService(CostRedisRepository costRedis, CostProperties properties) {
        this.costRedis = costRedis;
        this.properties = properties;
        this.zone = ZoneId.of(properties.getZone());
    }

    /** 超限抛 {@link QuotaExceededException}；调用方不要吞掉这个异常。 */
    public void check(String userId) {
        CostProperties.Quota quota = properties.getQuota();
        if (quota == null || !quota.isEnabled()) {
            return;
        }
        BigDecimal limit = BigDecimal.valueOf(quota.getDailyPerUser());
        if (limit.signum() <= 0) {
            return;
        }
        BigDecimal used;
        try {
            used = costRedis.userCost(userId, LocalDate.now(zone));
        } catch (RuntimeException e) {
            log.warn("quota check skipped (redis unavailable) user={}: {}", userId, e.toString());
            return;
        }
        if (used.compareTo(limit) >= 0) {
            throw new QuotaExceededException(limit, used);
        }
    }

    public QuotaView current(String userId) {
        CostProperties.Quota quota = properties.getQuota();
        BigDecimal limit = BigDecimal.valueOf(quota == null ? 0.0 : quota.getDailyPerUser());
        BigDecimal used;
        try {
            used = costRedis.userCost(userId, LocalDate.now(zone));
        } catch (RuntimeException e) {
            used = BigDecimal.ZERO;
        }
        BigDecimal remaining = limit.subtract(used).max(BigDecimal.ZERO);
        return new QuotaView(userId, limit, used, remaining, used.compareTo(limit) >= 0);
    }
}
