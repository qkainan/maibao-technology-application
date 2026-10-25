package com.maibao.tech.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.maibao.tech.usage.domain.CostMessage;

/**
 * 成本事件处理：幂等锚点（短事务）→ 累加（事务外）。
 *
 * <p><b>为什么累加不在事务里：</b>累加是"UPDATE 不中再 INSERT"，MySQL 的
 * REPEATABLE READ 下 UPDATE 不中会留间隙锁；如果和锚点插入同事务，INSERT 会被
 * 自己的锁挡住（历史故障，见 docs/03）。所以锚点用 REQUIRES_NEW 先提交，
 * 累加走自动提交，插入再用 REQUIRES_NEW 兜并发。</p>
 */
@Service
public class CostProcessor {

    private static final Logger log = LoggerFactory.getLogger(CostProcessor.class);

    private final CostDedupeService dedupe;
    private final CostAccumulator accumulator;

    public CostProcessor(CostDedupeService dedupe, CostAccumulator accumulator) {
        this.dedupe = dedupe;
        this.accumulator = accumulator;
    }

    public void process(CostMessage message) {
        dedupe.mark(message.bizId());
        try {
            accumulator.accumulate(message.userId(), message.model(), message.statDate(),
                    message.promptTokens(), message.completionTokens(), message.costAmount());
        } catch (RuntimeException e) {
            unmarkQuietly(message.bizId());
            throw e;
        }
        log.debug("cost accumulated: bizId={} user={} model={} cost={}",
                message.bizId(), message.userId(), message.model(), message.costAmount());
    }

    private void unmarkQuietly(String bizId) {
        try {
            dedupe.unmark(bizId);
        } catch (RuntimeException e) {
            log.error("failed to unmark cost message {}, retry may be skipped: {}", bizId, e.toString());
        }
    }
}
