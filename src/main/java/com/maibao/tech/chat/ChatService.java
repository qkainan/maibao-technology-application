package com.maibao.tech.chat;

import java.util.List;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.maibao.tech.adapter.AdapterRegistry;
import com.maibao.tech.adapter.AiProviderAdapter;
import com.maibao.tech.adapter.domain.ChatChunk;
import com.maibao.tech.adapter.domain.ChatMessage;
import com.maibao.tech.adapter.domain.ChatRequest;
import com.maibao.tech.usage.UsageService;

/**
 * 对话服务：选适配器、拼请求，并记录一次用量（流水 + 当天成本）。
 *
 * <p>埋点是旁路：写日志失败不应该影响对话本身，所以 {@link UsageService#record}
 * 内部自己吞掉成本累加异常。</p>
 */
@Service
public class ChatService {

    private static final String SOURCE_SYNC = "SYNC";
    private static final String SOURCE_STREAM = "STREAM";

    private final AdapterRegistry registry;
    private final UsageService usageService;

    public ChatService(AdapterRegistry registry, UsageService usageService) {
        this.registry = registry;
        this.usageService = usageService;
    }

    public String chat(String userId, String appKeyRef, String model, String prompt) {
        AiProviderAdapter adapter = registry.resolve(model);
        ChatRequest request = new ChatRequest(model, List.of(ChatMessage.user(prompt)), 0.7);
        long start = System.currentTimeMillis();
        try {
            String reply = adapter.chat(appKeyRef, request);
            usageService.record(userId, model, SOURCE_SYNC, prompt, reply,
                    System.currentTimeMillis() - start, true, null);
            return reply;
        } catch (RuntimeException e) {
            usageService.record(userId, model, SOURCE_SYNC, prompt, null,
                    System.currentTimeMillis() - start, false, e.getMessage());
            throw e;
        }
    }

    public void stream(String userId, String appKeyRef, String model, String prompt, Consumer<ChatChunk> sink) {
        AiProviderAdapter adapter = registry.resolve(model);
        ChatRequest request = new ChatRequest(model, List.of(ChatMessage.user(prompt)), 0.7);
        long start = System.currentTimeMillis();
        StringBuilder reply = new StringBuilder();
        try {
            adapter.stream(appKeyRef, request, chunk -> {
                if (!chunk.finished() && !chunk.reasoning()) {
                    reply.append(chunk.delta());
                }
                sink.accept(chunk);
            });
            usageService.record(userId, model, SOURCE_STREAM, prompt, reply.toString(),
                    System.currentTimeMillis() - start, true, null);
        } catch (RuntimeException e) {
            usageService.record(userId, model, SOURCE_STREAM, prompt, reply.toString(),
                    System.currentTimeMillis() - start, false, e.getMessage());
            throw e;
        }
    }
}
