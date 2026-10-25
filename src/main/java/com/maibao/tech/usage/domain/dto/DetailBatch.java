package com.maibao.tech.usage.domain.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 一批明细，作为<b>一条</b> MQ 消息投递。
 *
 * <p>攒批是这里的关键：消息量从"每次对话一条"降到"每 200ms 一条"，
 * broker 和消费者都轻松；代价是明细落库晚一个攒批周期（默认 200ms）。</p>
 */
public record DetailBatch(String batchId, List<DetailRecord> rows, Instant createdAt) {

    public static DetailBatch of(List<DetailRecord> rows) {
        return new DetailBatch(UUID.randomUUID().toString(), List.copyOf(rows), Instant.now());
    }
}
