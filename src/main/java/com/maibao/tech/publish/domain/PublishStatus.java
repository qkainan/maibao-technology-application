package com.maibao.tech.publish.domain;

/** 发布生命周期。是否可被调用属于另一个维度，不在这个枚举里。 */
public enum PublishStatus {
    SCHEDULED,
    PUBLISHING,
    SUCCESS,
    FAILED,
    CANCELLED
}
