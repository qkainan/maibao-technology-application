package com.maibao.tech.usage;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.maibao.tech.usage.domain.CostMessageLog;
import com.maibao.tech.usage.repository.CostMessageLogRepository;

/**
 * 成本消息的幂等锚点，独立短事务。
 *
 * <p>先提交锚点（重复投递会撞唯一键），再执行累加；累加失败时撤销锚点，
 * 重试才有机会重跑。</p>
 */
@Service
public class CostDedupeService {

    private final CostMessageLogRepository repository;

    public CostDedupeService(CostMessageLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void mark(String messageId) {
        repository.saveAndFlush(new CostMessageLog(messageId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void unmark(String messageId) {
        repository.deleteByMessageId(messageId);
    }
}
