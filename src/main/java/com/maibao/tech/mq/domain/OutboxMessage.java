package com.maibao.tech.mq.domain;

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
 * 本地消息表（outbox）。
 *
 * <p>业务先落这一行（和业务同事务），再由定时任务投递。这样"业务成功但消息没发出去"
 * 不会发生——消息只是稍晚一点到，而不是永久丢失。</p>
 */
@Entity
@Table(name = "mq_outbox",
        uniqueConstraints = @UniqueConstraint(name = "uk_outbox_message", columnNames = "message_id"),
        indexes = @Index(name = "idx_outbox_status_retry", columnList = "status, next_retry_at"))
public class OutboxMessage {

    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";
    public static final String CONSUMED = "CONSUMED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, length = 64)
    private String messageId;

    @Column(name = "exchange_name", nullable = false, length = 64)
    private String exchangeName;

    @Column(name = "routing_key", nullable = false, length = 64)
    private String routingKey;

    @Column(name = "payload", nullable = false, length = 1024)
    private String payload;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_retry_at", nullable = false)
    private Instant nextRetryAt;

    @Column(name = "last_error", length = 255)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected OutboxMessage() {
    }

    public OutboxMessage(String messageId, String exchangeName, String routingKey, String payload) {
        this.messageId = messageId;
        this.exchangeName = exchangeName;
        this.routingKey = routingKey;
        this.payload = payload;
        this.status = PENDING;
        this.attempts = 0;
        this.nextRetryAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getExchangeName() {
        return exchangeName;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public String getPayload() {
        return payload;
    }

    public String getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
