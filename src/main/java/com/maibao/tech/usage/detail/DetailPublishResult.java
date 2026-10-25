package com.maibao.tech.usage.detail;

/**
 * 明细消息的发布结果（confirm 回调 → 事件 → DetailBuffer 处理）。
 *
 * <p>用事件而不是直接注入 {@code DetailBuffer}：{@code RabbitTemplateCustomizer} 在
 * {@code RabbitTemplate} 创建之前就要装配好，直接依赖 {@code DetailBuffer}（它又依赖
 * {@code RabbitTemplate}）会形成循环依赖。</p>
 */
public record DetailPublishResult(String batchId, boolean ack, String cause) {
}
