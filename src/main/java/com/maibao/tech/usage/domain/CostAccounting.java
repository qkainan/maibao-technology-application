package com.maibao.tech.usage.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 成本按「用户 × 模型 × 天」聚合。
 *
 * <p>为什么不用"每次查询插一行再 SUM"：对话流水增长很快，报表要的是可索引的小表。
 * 累加用 `UPDATE ... SET x = x + ?`，并发下由唯一键兜底，重复插入会被约束挡住。</p>
 */
@Entity
@Table(name = "cost_accounting",
        uniqueConstraints = @UniqueConstraint(name = "uk_cost_user_model_date",
                columnNames = {"user_id", "model", "stat_date"}),
        indexes = @Index(name = "idx_cost_user_date", columnList = "user_id, stat_date"))
public class CostAccounting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "model", nullable = false, length = 64)
    private String model;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "request_count", nullable = false)
    private long requestCount;

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    @Column(name = "cost_amount", nullable = false, precision = 12, scale = 6)
    private BigDecimal costAmount;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected CostAccounting() {
    }

    public CostAccounting(String userId, String model, LocalDate statDate, long requestCount,
                          long promptTokens, long completionTokens, BigDecimal costAmount, String currency) {
        this.userId = userId;
        this.model = model;
        this.statDate = statDate;
        this.requestCount = requestCount;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = promptTokens + completionTokens;
        this.costAmount = costAmount;
        this.currency = currency;
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getModel() {
        return model;
    }

    public LocalDate getStatDate() {
        return statDate;
    }

    public long getRequestCount() {
        return requestCount;
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public BigDecimal getCostAmount() {
        return costAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
