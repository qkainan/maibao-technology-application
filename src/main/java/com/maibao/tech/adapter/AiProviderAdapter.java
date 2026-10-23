package com.maibao.tech.adapter;

import java.util.function.Consumer;

/**
 * 适配器接口。每个厂商实现自己的协议差异，上层只调用这里。
 *
 * <p>对应简历：以适配器模式统一新旧 LLM 节点的出入参，避免 N×M 的适配爆炸。</p>
 */
public interface AiProviderAdapter {

    /** 适配器名，用于按 model 前缀路由，如 openai / mock / qwen */
    String name();

    /** 同步调用 */
    String chat(String appKeyRef, ChatRequest request);

    /** 流式调用：把厂商的增量转成统一的 ChatChunk 往下游推 */
    void stream(String appKeyRef, ChatRequest request, Consumer<ChatChunk> sink);
}
