package com.maibao.tech.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.maibao.tech.usage.util.CostProperties;

/**
 * 计价：按 model 的冒号前缀取单价，把 token 折算成金额。
 *
 * <p>取价规则放在这里而不是配置类 —— 配置类只描述"配了什么"，
 * "怎么用这些配置"属于业务。</p>
 */
@Component
public class CostCalculator {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final int SCALE = 6;
    private static final String DEFAULT_PREFIX = "default";

    private final CostProperties properties;

    public CostCalculator(CostProperties properties) {
        this.properties = properties;
    }

    public BigDecimal amount(String model, int promptTokens, int completionTokens) {
        CostProperties.Price price = priceFor(model);
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

    private CostProperties.Price priceFor(String model) {
        Map<String, CostProperties.Price> pricing = properties.getPricing();
        CostProperties.Price price = pricing.get(prefixOf(model));
        if (price == null) {
            price = pricing.get(DEFAULT_PREFIX);
        }
        return price == null ? new CostProperties.Price() : price;
    }

    private static String prefixOf(String model) {
        if (model == null) {
            return DEFAULT_PREFIX;
        }
        int idx = model.indexOf(':');
        return idx > 0 ? model.substring(0, idx) : model;
    }
}
