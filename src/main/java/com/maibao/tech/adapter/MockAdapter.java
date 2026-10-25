package com.maibao.tech.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.maibao.tech.adapter.domain.Capabilities;
import com.maibao.tech.adapter.domain.ChatChunk;
import com.maibao.tech.adapter.domain.ChatRequest;

/** 无 key 时的兜底适配器，同时用于单测。 */
@Component
public class MockAdapter implements AiProviderAdapter {

    private final long charDelayMs;

    public MockAdapter(@Value("${app.provider.mock.char-delay-ms:15}") long charDelayMs) {
        this.charDelayMs = charDelayMs;
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.textOnly(8192);
    }

    @Override
    public String chat(String appKeyRef, ChatRequest request) {
        String prompt = request.messages().isEmpty() ? "" : request.messages().get(request.messages().size() - 1).content();
        return "[mock] 收到：" + prompt;
    }

    /**
     * 逐字推送并留出间隔，用来真实验证心跳、seq 与"客户端断开后停止推送"。
     */
    @Override
    public void stream(String appKeyRef, ChatRequest request, ChunkSink sink) {
        String full = chat(appKeyRef, request);
        sink.onChunk(ChatChunk.reasoning("先理解问题，再组织回答。"));
        for (char c : full.toCharArray()) {
            if (sink.cancelled()) {
                throw new StreamCancelledException();
            }
            sink.onChunk(ChatChunk.text(String.valueOf(c)));
            sleepQuietly();
        }
        sink.onChunk(ChatChunk.done());
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(charDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StreamCancelledException();
        }
    }
}
