package com.maibao.tech.usage.domain.vo;

import java.math.BigDecimal;

/** 配额展示：给前端提示"还剩多少"。 */
public record QuotaView(String userId, BigDecimal limit, BigDecimal used,
                        BigDecimal remaining, boolean exceeded) {
}
