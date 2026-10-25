package com.maibao.tech.usage.util;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 成本单价配置：按 model 前缀取价，取不到用 default。 */
@Component
@ConfigurationProperties(prefix = "app.cost")
public class CostProperties {

    private String currency = "CNY";

    /** 账期时区：与 JDBC 的 serverTimezone 必须一致 */
    private String zone = "Asia/Shanghai";

    /** 用户配额 */
    private Quota quota = new Quota();

    /** Redis 累加 */
    private Redis redis = new Redis();

    /** 明细批量写 */
    private Detail detail = new Detail();

    /** 落盘间隔（毫秒） */
    private long flushMs = 60_000L;

    /** 日结 cron */
    private String settleCron = "0 5 0 * * *";

    private Map<String, Price> pricing = new LinkedHashMap<>();

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public Quota getQuota() {
        return quota;
    }

    public void setQuota(Quota quota) {
        this.quota = quota;
    }

    public Redis getRedis() {
        return redis;
    }

    public void setRedis(Redis redis) {
        this.redis = redis;
    }

    public Detail getDetail() {
        return detail;
    }

    public void setDetail(Detail detail) {
        this.detail = detail;
    }

    public long getFlushMs() {
        return flushMs;
    }

    public void setFlushMs(long flushMs) {
        this.flushMs = flushMs;
    }

    public String getSettleCron() {
        return settleCron;
    }

    public void setSettleCron(String settleCron) {
        this.settleCron = settleCron;
    }

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

    /** app.cost.quota.* */
    public static class Quota {

        /** 关掉就是不做配额校验（压测/调试用） */
        private boolean enabled = true;

        /** 每用户每日额度（元），<=0 表示不限制 */
        private double dailyPerUser = 100.0;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getDailyPerUser() {
            return dailyPerUser;
        }

        public void setDailyPerUser(double dailyPerUser) {
            this.dailyPerUser = dailyPerUser;
        }
    }

    /** app.cost.redis.* */
    public static class Redis {

        private String keyPrefix = "maibao:cost";

        /** key 保留天数，跨天补数据用 */
        private int ttlDays = 3;

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public int getTtlDays() {
            return ttlDays;
        }

        public void setTtlDays(int ttlDays) {
            this.ttlDays = ttlDays;
        }
    }

    /** app.cost.detail.* */
    public static class Detail {

        /** 单批写库条数 */
        private int batchSize = 2000;

        /** 本地队列容量，满了就丢 */
        private int queueCapacity = 20000;

        /** 明细保留天数 */
        private int retainDays = 7;

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public int getRetainDays() {
            return retainDays;
        }

        public void setRetainDays(int retainDays) {
            this.retainDays = retainDays;
        }
    }
}
