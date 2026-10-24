package com.maibao.tech.publish.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 发布记录：一张表承载立即 / 定时 / 灰度三种发布方式。
 *
 * <p>字段按四类拆分：生命周期 status、可调用性（未实现）、来源标记 isScheduled、进度 grayPercent。</p>
 */
@Entity
@Table(name = "publish_record",
        indexes = {
                // 到期扫描的查询条件是 status = ? AND scheduled_at <= ?：
                // 等值列在前、范围列在后，才能用上这条联合索引。
                @Index(name = "idx_status_scheduled_at", columnList = "status, scheduled_at")
        })
public class PublishRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PublishStatus status;

    @Column(name = "is_scheduled", nullable = false)
    private int isScheduled;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    /** 被抢占（SCHEDULED -> PUBLISHING）的时刻，用于识别卡死记录 */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "gray_percent", nullable = false)
    private int grayPercent;

    @Column(name = "fail_reason", length = 255)
    private String failReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected PublishRecord() {
    }

    public PublishRecord(String nodeId, Instant scheduledAt) {
        this.nodeId = nodeId;
        this.isScheduled = scheduledAt == null ? 0 : 1;
        // 立即发布也先落成 SCHEDULED，由扫描器统一"认领 -> 执行"，
        // 这样立即/定时两种入口走同一条状态链路，不会有两套并发控制。
        this.scheduledAt = scheduledAt == null ? Instant.now() : scheduledAt;
        this.status = PublishStatus.SCHEDULED;
        this.grayPercent = 100;
    }

    public Long getId() {
        return id;
    }

    public String getNodeId() {
        return nodeId;
    }

    public PublishStatus getStatus() {
        return status;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public int getGrayPercent() {
        return grayPercent;
    }

    public String getFailReason() {
        return failReason;
    }

    public int getIsScheduled() {
        return isScheduled;
    }
}
