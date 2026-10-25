package com.maibao.tech.usage.domain.po;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 一次 AI 对话的流水：谁、用什么模型、问了什么、花了多少 token / 多少钱。
 *
 * <p>这是"模拟业务"的埋点表：prompt / reply 只保留截断后的内容，token 数是估算值
 * （见 {@code TokenEstimator}），成本和真实厂商账单无关。</p>
 */
@Entity
@Table(name = "user_query_log",
        uniqueConstraints = @UniqueConstraint(name = "uk_query_request", columnNames = "request_id"),
        indexes = {
                @Index(name = "idx_query_user_id", columnList = "user_id, id"),
                @Index(name = "idx_query_created", columnList = "created_at")
        })
public class UserQueryLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "model", nullable = false, length = 64)
    private String model;

    /** SYNC / STREAM */
    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "prompt", length = 2000)
    private String prompt;

    @Column(name = "reply", length = 4000)
    private String reply;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private int totalTokens;

    @Column(name = "cost_amount", nullable = false, precision = 12, scale = 6)
    private BigDecimal costAmount;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    /** SUCCESS / FAILED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "error_msg", length = 255)
    private String errorMsg;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected UserQueryLog() {
    }

    public UserQueryLog(String requestId, String userId, String model, String source,
                        String prompt, String reply, int promptTokens, int completionTokens,
                        BigDecimal costAmount, int latencyMs, String status, String errorMsg) {
        this.requestId = requestId;
        this.userId = userId;
        this.model = model;
        this.source = source;
        this.prompt = prompt;
        this.reply = reply;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = promptTokens + completionTokens;
        this.costAmount = costAmount;
        this.latencyMs = latencyMs;
        this.status = status;
        this.errorMsg = errorMsg;
    }

    public Long getId() {
        return id;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getUserId() {
        return userId;
    }

    public String getModel() {
        return model;
    }

    public String getSource() {
        return source;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getReply() {
        return reply;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public BigDecimal getCostAmount() {
        return costAmount;
    }

    public int getLatencyMs() {
        return latencyMs;
    }

    public String getStatus() {
        return status;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
