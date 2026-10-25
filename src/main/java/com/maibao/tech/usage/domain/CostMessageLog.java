package com.maibao.tech.usage.domain;

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
 * 成本消息去重表：一条成本消息只允许累加一次。
 *
 * <p>只服务"防重复"，不承担审计，所以保留 7 天后由定时任务清理。</p>
 */
@Entity
@Table(name = "cost_message_log",
        uniqueConstraints = @UniqueConstraint(name = "uk_cost_message", columnNames = "message_id"),
        indexes = @Index(name = "idx_cost_message_created", columnList = "created_at"))
public class CostMessageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, length = 64)
    private String messageId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected CostMessageLog() {
    }

    public CostMessageLog(String messageId) {
        this.messageId = messageId;
    }

    public Long getId() {
        return id;
    }

    public String getMessageId() {
        return messageId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
