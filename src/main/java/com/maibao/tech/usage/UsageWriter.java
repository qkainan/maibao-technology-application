package com.maibao.tech.usage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.maibao.tech.mq.OutboxService;
import com.maibao.tech.usage.domain.CostMessage;
import com.maibao.tech.usage.domain.UserQueryLog;
import com.maibao.tech.usage.repository.UserQueryLogRepository;

/**
 * 明细与 outbox 的同事务写入。
 *
 * <p>明细是权威，成本消息是派生：两者必须一起提交，否则会出现"有账没消息"
 * （对账能发现，但没必要制造）。</p>
 */
@Service
public class UsageWriter {

    private final UserQueryLogRepository queryLogRepository;
    private final OutboxService outboxService;
    private final String exchange;
    private final String routingKey;

    public UsageWriter(UserQueryLogRepository queryLogRepository,
                       OutboxService outboxService,
                       @Value("${app.cost.exchange}") String exchange,
                       @Value("${app.cost.routing-key}") String routingKey) {
        this.queryLogRepository = queryLogRepository;
        this.outboxService = outboxService;
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    @Transactional
    public void saveAndEnqueue(UserQueryLog row, CostMessage message) {
        queryLogRepository.save(row);
        outboxService.enqueue(message.bizId(), exchange, routingKey, message);
    }
}
