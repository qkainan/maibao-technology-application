package com.maibao.tech.usage.util;


import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

/** 按单价把 token 折算成金额。 */
@Component
public class CostCalculator {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final int SCALE = 6;

    private final CostProperties properties;

    public CostCalculator(CostProperties properties) {
        this.properties = properties;
    }

    public BigDecimal amount(String model, int promptTokens, int completionTokens) {
        CostProperties.Price price = properties.priceFor(model);
        BigDecimal input = BigDecimal.valueOf(price.getInputPer1k())
                .multiply(BigDecimal.valueOf(promptTokens))
                .divide(THOUSAND, SCALE, RoundingMode.HALF_UP);
        BigDecimal output = BigDecimal.valueOf(price.getOutputPer1k())
                .multiply(BigDecimal.valueOf(completionTokens))
                .divide(THOUSAND, SCALE, RoundingMode.HALF_UP);
        return input.add(output).setScale(SCALE, RoundingMode.HALF_UP);
    }

    public String currency() {
        return properties.getCurrency();
    }
}
