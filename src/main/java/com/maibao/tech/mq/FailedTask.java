package com.maibao.tech.mq;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 消费失败的落库记录。
 *
 * <p>重试次数用尽后写这里，而不是让消息悬在队列里或直接丢掉 —— 出问题时能查，
 * 也给了人工补偿的入口。</p>
 */
@Entity
@Table(name = "failed_task",
        indexes = @Index(name = "idx_failed_task_id", columnList = "task_id"))
public class FailedTask {

    private static final int MAX_TEXT = 512;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "payload", length = MAX_TEXT)
    private String payload;

    @Column(name = "reason", length = MAX_TEXT)
    private String reason;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected FailedTask() {
    }

    public FailedTask(String taskId, String payload, String reason, int attempts) {
        this.taskId = taskId;
        this.payload = truncate(payload);
        this.reason = truncate(reason);
        this.attempts = attempts;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }

    public Long getId() {
        return id;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getPayload() {
        return payload;
    }

    public String getReason() {
        return reason;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
