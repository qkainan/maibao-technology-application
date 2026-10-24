package com.maibao.tech.adapter.domain;

/**
 * 统一出参（流式增量）。
 *
 * @param delta     本段文本
 * @param reasoning 是否思维链增量（对应 reasoning_content）
 * @param finished  是否结束帧
 */
public record ChatChunk(String delta, boolean reasoning, boolean finished) {

    public static ChatChunk text(String delta) {
        return new ChatChunk(delta, false, false);
    }

    public static ChatChunk reasoning(String delta) {
        return new ChatChunk(delta, true, false);
    }

    public static ChatChunk done() {
        return new ChatChunk("", false, true);
    }
}
