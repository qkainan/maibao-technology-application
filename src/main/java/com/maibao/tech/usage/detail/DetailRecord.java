package com.maibao.tech.usage.detail;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 一条对话明细。
 *
 * <p>写路径用它而不是 JPA 实体：实体里带着 {@code @Entity}、自增主键这些持久化细节，
 * 当消息体序列化会把它们一起带上，读消息的人还得反推哪些字段是业务真正关心的。</p>
 */
public record DetailRecord(String requestId,
                           String userId,
                           String model,
                           String source,
                           String prompt,
                           String reply,
                           int promptTokens,
                           int completionTokens,
                           BigDecimal costAmount,
                           int latencyMs,
                           String status,
                           String errorMsg,
                           Instant createdAt) {

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
