package com.maibao.tech.mq.domain;

import java.time.Instant;

/** 异步任务消息。taskId 是对外幂等键。 */
public record TaskMessage(String taskId, String payload, Instant createdAt) {

    public static TaskMessage of(String taskId, String payload) {
        return new TaskMessage(taskId, payload, Instant.now());
    }
}
