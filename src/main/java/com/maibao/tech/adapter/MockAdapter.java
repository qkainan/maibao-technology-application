package com.maibao.tech.adapter;

import java.util.function.Consumer;

import org.springframework.stereotype.Component;

/** 无 key 时的兜底适配器：让项目开箱可跑，也用于压测和单测。 */
@Component
public class MockAdapter implements AiProviderAdapter {

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public String chat(String appKeyRef, ChatRequest request) {
        String prompt = request.messages().isEmpty() ? "" : request.messages().get(request.messages().size() - 1).content();
        return "[mock] 收到：" + prompt;
    }

    @Override
    public void stream(String appKeyRef, ChatRequest request, Consumer<ChatChunk> sink) {
        String full = chat(appKeyRef, request);
        sink.accept(ChatChunk.reasoning("先理解问题，再组织回答。"));
        for (char c : full.toCharArray()) {
            sink.accept(ChatChunk.text(String.valueOf(c)));
        }
        sink.accept(ChatChunk.done());
    }
}
