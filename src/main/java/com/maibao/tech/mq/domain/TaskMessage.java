package com.maibao.tech.mq.domain;

import java.time.Instant;

/**
 * 异步任务消息。taskId 是对外幂等键。
 *
 * <p>model / userId 可以为空：空时用 app.task.model 与 app.task.user-id 的默认值，
 * 兼容这条链路还没有这两个字段时留下的老消息。</p>
 */
public record TaskMessage(String taskId, String payload, Instant createdAt, String model, String userId) {

    public static TaskMessage of(String taskId, String payload) {
        return new TaskMessage(taskId, payload, Instant.now(), null, null);
    }

    public static TaskMessage of(String taskId, String payload, String model, String userId) {
        return new TaskMessage(taskId, payload, Instant.now(), model, userId);
    }
}
