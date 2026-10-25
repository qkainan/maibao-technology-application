package com.maibao.tech.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(CostCalculator.class);

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final int SCALE = 6;
    private static final String DEFAULT_PREFIX = "default";

    private final CostProperties properties;

    public CostCalculator(CostProperties properties) {
        this.properties = properties;
    }

    /**
     * 启动自检：价表为空意味着所有金额都算成 0，账会静默丢失。
     *
     * <p>这类配置错误不会抛异常，只会让数字一直是 0 —— 所以在启动时就喊出来，
     * 而不是等对账时才发现"钱没记上"。</p>
     */
    @PostConstruct
    void warnIfPricingMissing() {
        if (properties.getPricing() == null || properties.getPricing().isEmpty()) {
            log.error("app.cost.pricing 为空：所有调用的费用都会算成 0，请检查配置");
        }
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
