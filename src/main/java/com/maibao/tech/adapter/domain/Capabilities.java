package com.maibao.tech.adapter.domain;

/**
 * 适配器能力声明。
 *
 * <p>上层（控制器、前端模型列表、参数校验、降级策略）都靠它判断能不能做某件事，
 * 而不是靠 {@code if (model.startsWith("gpt-4o"))} 这种硬编码。</p>
 */
public record Capabilities(boolean stream, boolean vision, boolean toolCall, int maxTokens) {

    public static Capabilities textOnly(int maxTokens) {
        return new Capabilities(true, false, false, maxTokens);
    }
}
