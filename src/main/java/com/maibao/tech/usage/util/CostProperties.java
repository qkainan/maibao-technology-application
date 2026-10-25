package com.maibao.tech.usage.util;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 成本相关配置。这里只放数据，不放算价、不放时区解析 —— 那些属于 {@code CostCalculator} 和调用方。 */
@Component
@ConfigurationProperties(prefix = "app.cost")
public class CostProperties {

    /** 币种，落在聚合行上 */
    private String currency = "CNY";

    /** 账期时区，必须与 JDBC 的 serverTimezone 一致 */
    private String zone = "Asia/Shanghai";

    /** 落盘间隔（毫秒） */
    private long flushMs = 60_000L;

    /** 日结 cron，默认每天 00:05 */
    private String settleCron = "0 5 0 * * *";

    /** 对账间隔（毫秒） */
    private long reconcileMs = 300_000L;

    private Quota quota = new Quota();

    private Redis redis = new Redis();

    private Detail detail = new Detail();

    /** 单价：key 是 model 的冒号前缀（mock / openai），取不到用 default */
    private Map<String, Price> pricing = new LinkedHashMap<>();

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
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

    public long getReconcileMs() {
        return reconcileMs;
    }

    public void setReconcileMs(long reconcileMs) {
        this.reconcileMs = reconcileMs;
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

    public Map<String, Price> getPricing() {
        return pricing;
    }

    public void setPricing(Map<String, Price> pricing) {
        this.pricing = pricing;
    }

    /** app.cost.pricing.* */
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

        /** 关掉就不做配额校验（压测/调试用） */
        private boolean enabled = true;

        /** 每用户每日额度（元），<= 0 表示不限制 */
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

    /** app.cost.detail.*：明细的批量缓冲与落库方式 */
    public static class Detail {

        /**
         * 落库方式：{@code mq} 走消息队列（崩溃不丢、可重投），
         * {@code local} 本地直写（不依赖 broker，但进程被杀会丢最后一批）。
         */
        private String mode = "mq";

        /** 攒批间隔（毫秒） */
        private long flushMs = 200L;

        /** 单批最大条数（也是消费端单次写入的行数） */
        private int batchSize = 2000;

        /** 本地队列容量，满了就丢弃并计数 */
        private int queueCapacity = 20000;

        /** 明细保留天数 */
        private int retainDays = 7;

        private String exchange = "maibao.detail.exchange";
        private String queue = "maibao.detail.queue";
        private String routingKey = "maibao.detail";
        private String dlxExchange = "maibao.detail.dlx";
        private String dlqQueue = "maibao.detail.dlq";
        private String dlqRoutingKey = "maibao.detail.dlq";

        /** 消费失败后的重投次数上限 */
        private int maxRetries = 3;

        public boolean isViaMq() {
            return "mq".equalsIgnoreCase(mode);
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public long getFlushMs() {
            return flushMs;
        }

        public void setFlushMs(long flushMs) {
            this.flushMs = flushMs;
        }

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

        public String getExchange() {
            return exchange;
        }

        public void setExchange(String exchange) {
            this.exchange = exchange;
        }

        public String getQueue() {
            return queue;
        }

        public void setQueue(String queue) {
            this.queue = queue;
        }

        public String getRoutingKey() {
            return routingKey;
        }

        public void setRoutingKey(String routingKey) {
            this.routingKey = routingKey;
        }

        public String getDlxExchange() {
            return dlxExchange;
        }

        public void setDlxExchange(String dlxExchange) {
            this.dlxExchange = dlxExchange;
        }

        public String getDlqQueue() {
            return dlqQueue;
        }

        public void setDlqQueue(String dlqQueue) {
            this.dlqQueue = dlqQueue;
        }

        public String getDlqRoutingKey() {
            return dlqRoutingKey;
        }

        public void setDlqRoutingKey(String dlqRoutingKey) {
            this.dlqRoutingKey = dlqRoutingKey;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }
    }
}
