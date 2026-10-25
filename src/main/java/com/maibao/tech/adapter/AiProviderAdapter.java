package com.maibao.tech.adapter;

import com.maibao.tech.adapter.domain.Capabilities;
import com.maibao.tech.adapter.domain.ChatRequest;

/**
 * 适配器接口。每个厂商实现自己的协议差异，上层只调用这里。
 *
 * <p>设计目标：用适配器模式统一各厂商 LLM 节点的出入参，避免 N×M 的适配爆炸。</p>
 */
public interface AiProviderAdapter {

    /** 适配器名，用于按 model 前缀路由，如 openai / mock / qwen */
    String name();

    /** 能力声明：上层据此做参数校验与降级，不靠 model 名硬编码 */
    Capabilities capabilities();

    /** 同步调用 */
    String chat(String appKeyRef, ChatRequest request);

    /** 流式调用：把厂商的增量转成统一的 ChatChunk 往下游推 */
    void stream(String appKeyRef, ChatRequest request, ChunkSink sink);
}
