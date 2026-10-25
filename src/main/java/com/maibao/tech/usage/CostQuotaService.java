package com.maibao.tech.usage;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.maibao.tech.usage.domain.QuotaView;

/**
 * 用户配额：调用模型<b>之前</b>校验，超限直接拒绝，不产生任何模型调用与费用。
 *
 * <p>数据源是 Redis 的当日累计（{@link CostRedisStore#userCost}），O(1) 读，
 * 所以放在请求路径上也不会成为瓶颈。失败打开策略：Redis 不可用时<b>放行</b>
 * （配额是成本控制，不是安全边界，不能因为缓存故障阻断业务）。</p>
 */
@Service
public class CostQuotaService {

    private static final Logger log = LoggerFactory.getLogger(CostQuotaService.class);

    private final CostRedisStore store;
    private final CostProperties props;

    public CostQuotaService(CostRedisStore store, CostProperties props) {
        this.store = store;
        this.props = props;
    }

    /** 超限抛 {@link QuotaExceededException}；调用方不要吞掉这个异常。 */
    public void check(String userId) {
        CostProperties.Quota quota = props.getQuota();
        if (quota == null || !quota.isEnabled()) {
            return;
        }
        BigDecimal limit = BigDecimal.valueOf(quota.getDailyPerUser());
        if (limit.signum() <= 0) {
            return;
        }
        BigDecimal used;
        try {
            used = store.userCost(userId, LocalDate.now(props.zoneId()));
        } catch (RuntimeException e) {
            log.warn("quota check skipped (redis unavailable) user={}: {}", userId, e.toString());
            return;
        }
        if (used.compareTo(limit) >= 0) {
            throw new QuotaExceededException(limit, used);
        }
    }

    public QuotaView current(String userId) {
        CostProperties.Quota quota = props.getQuota();
        BigDecimal limit = BigDecimal.valueOf(quota == null ? 0.0 : quota.getDailyPerUser());
        BigDecimal used;
        try {
            used = store.userCost(userId, LocalDate.now(props.zoneId()));
        } catch (RuntimeException e) {
            used = BigDecimal.ZERO;
        }
        BigDecimal remaining = limit.subtract(used).max(BigDecimal.ZERO);
        return new QuotaView(userId, limit, used, remaining, used.compareTo(limit) >= 0);
    }
}
