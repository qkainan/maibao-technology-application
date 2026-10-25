package com.maibao.tech.usage.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 成本事件：一次成功对话的用量与费用。
 *
 * <p>通过 outbox → MQ 投递，消费端负责幂等累加。{@code bizId} 就是
 * {@code user_query_log.request_id}，也是幂等键；{@code statDate} 由生产端按
 * Asia/Shanghai 算好，避免消费端跨天重投时落到错误账期。</p>
 */
public record CostMessage(String bizId,
                          String userId,
                          String model,
                          LocalDate statDate,
                          int promptTokens,
                          int completionTokens,
                          BigDecimal costAmount,
                          String currency) {
}
