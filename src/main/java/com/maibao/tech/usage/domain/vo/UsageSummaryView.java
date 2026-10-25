package com.maibao.tech.usage.domain.vo;

import java.math.BigDecimal;

/** 一个用户的累计用量。 */
public record UsageSummaryView(String userId,
                               long requestCount,
                               long totalTokens,
                               BigDecimal costAmount,
                               String currency) {
}
