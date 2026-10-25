package com.maibao.tech.usage.util;

import com.maibao.tech.usage.CostProperties;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CostCalculatorTest {

    private CostCalculator calculator;

    @BeforeEach
    void setUp() {
        CostProperties properties = new CostProperties();
        properties.setCurrency("CNY");

        CostProperties.Price mock = new CostProperties.Price();
        mock.setInputPer1k(0.001);
        mock.setOutputPer1k(0.002);

        CostProperties.Price fallback = new CostProperties.Price();
        fallback.setInputPer1k(0.002);
        fallback.setOutputPer1k(0.006);

        properties.setPricing(Map.of("mock", mock, "default", fallback));
        calculator = new CostCalculator(properties);
    }

    @Test
    void should_price_by_model_prefix() {
        // 1000 prompt + 500 completion：0.001 + 0.001
        assertThat(calculator.amount("mock:echo", 1000, 500))
                .isEqualByComparingTo(new BigDecimal("0.002000"));
    }

    @Test
    void should_fallback_to_default_price_for_unknown_model() {
        // 1000 prompt：0.002
        assertThat(calculator.amount("openai:gpt-4o-mini", 1000, 0))
                .isEqualByComparingTo(new BigDecimal("0.002000"));
    }

    @Test
    void should_return_zero_when_no_tokens() {
        assertThat(calculator.amount("mock:echo", 0, 0)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void should_expose_configured_currency() {
        assertThat(calculator.currency()).isEqualTo("CNY");
    }
}
