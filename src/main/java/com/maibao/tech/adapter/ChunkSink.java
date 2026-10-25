package com.maibao.tech.adapter;

import com.maibao.tech.adapter.domain.ChatChunk;

/**
 * 流式输出端：适配器把归一后的增量推给它。
 *
 * <p>{@link #cancelled()} 让适配器在客户端断开后尽快停止读取上游，
 * 避免"用户走了还在烧 token"。</p>
 */
public interface ChunkSink {

    void onChunk(ChatChunk chunk);

    boolean cancelled();
}
