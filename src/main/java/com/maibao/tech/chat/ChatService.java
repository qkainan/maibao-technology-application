package com.maibao.tech.chat;

import java.util.List;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.maibao.tech.adapter.AdapterRegistry;
import com.maibao.tech.adapter.AiProviderAdapter;


import com.maibao.tech.adapter.domain.ChatChunk;
import com.maibao.tech.adapter.domain.ChatMessage;
import com.maibao.tech.adapter.domain.ChatRequest;


@Service
public class ChatService {

    private final AdapterRegistry registry;

    public ChatService(AdapterRegistry registry) {
        this.registry = registry;
    }

    public String chat(String appKeyRef, String model, String prompt) {
        AiProviderAdapter adapter = registry.resolve(model);
        ChatRequest req = new ChatRequest(model, List.of(ChatMessage.user(prompt)), 0.7);
        return adapter.chat(appKeyRef, req);
    }

    public void stream(String appKeyRef, String model, String prompt, Consumer<ChatChunk> sink) {
        AiProviderAdapter adapter = registry.resolve(model);
        ChatRequest req = new ChatRequest(model, List.of(ChatMessage.user(prompt)), 0.7);
        adapter.stream(appKeyRef, req, sink);
    }
}
