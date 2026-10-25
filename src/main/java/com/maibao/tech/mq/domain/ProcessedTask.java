package com.maibao.tech.mq.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 消费去重表：靠唯一约束实现幂等（而不是"先查后插"）。 */
@Entity
@Table(name = "processed_task",
        uniqueConstraints = @UniqueConstraint(name = "uk_processed_task_id", columnNames = "task_id"))
public class ProcessedTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ProcessedTask() {
    }

    public ProcessedTask(String taskId) {
        this.taskId = taskId;
    }

    public Long getId() {
        return id;
    }

    public String getTaskId() {
        return taskId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
