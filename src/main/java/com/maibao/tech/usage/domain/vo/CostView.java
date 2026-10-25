package com.maibao.tech.usage.domain.vo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import com.maibao.tech.usage.domain.po.CostAccounting;

/** 成本聚合的一行视图。 */
public record CostView(String model,
                       LocalDate statDate,
                       long requestCount,
                       long promptTokens,
                       long completionTokens,
                       long totalTokens,
                       BigDecimal costAmount,
                       String currency,
                       Instant updatedAt) {

    public static CostView of(CostAccounting row) {
        return new CostView(row.getModel(), row.getStatDate(), row.getRequestCount(),
                row.getPromptTokens(), row.getCompletionTokens(), row.getTotalTokens(),
                row.getCostAmount(), row.getCurrency(), row.getUpdatedAt());
    }
}
