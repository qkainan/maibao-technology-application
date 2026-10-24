package com.maibao.tech.usage;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 成本单价配置：按 model 前缀取价，取不到用 default。 */
@Component
@ConfigurationProperties(prefix = "app.cost")
public class CostProperties {

    private String currency = "CNY";

    private Map<String, Price> pricing = new LinkedHashMap<>();

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Map<String, Price> getPricing() {
        return pricing;
    }

    public void setPricing(Map<String, Price> pricing) {
        this.pricing = pricing;
    }

    public Price priceFor(String model) {
        String prefix = prefixOf(model);
        Price price = pricing.get(prefix);
        if (price == null) {
            price = pricing.get("default");
        }
        return price == null ? new Price() : price;
    }

    private static String prefixOf(String model) {
        if (model == null) {
            return "default";
        }
        int idx = model.indexOf(':');
        return idx > 0 ? model.substring(0, idx) : model;
    }

    public static class Price {

        private double inputPer1k;
        private double outputPer1k;

        public double getInputPer1k() {
            return inputPer1k;
        }

        public void setInputPer1k(double inputPer1k) {
            this.inputPer1k = inputPer1k;
        }

        public double getOutputPer1k() {
            return outputPer1k;
        }

        public void setOutputPer1k(double outputPer1k) {
            this.outputPer1k = outputPer1k;
        }
    }
}
