package com.maibao.tech.chat;

import java.util.List;

import org.springframework.stereotype.Service;

import com.maibao.tech.adapter.AdapterRegistry;
import com.maibao.tech.adapter.AiProviderAdapter;
import com.maibao.tech.adapter.ChunkSink;
import com.maibao.tech.adapter.StreamCancelledException;
import com.maibao.tech.adapter.domain.ChatChunk;
import com.maibao.tech.adapter.domain.ChatMessage;
import com.maibao.tech.adapter.domain.ChatRequest;
import com.maibao.tech.adapter.domain.ProviderException;
import com.maibao.tech.common.BizException;
import com.maibao.tech.usage.UsageService;

/**
 * 对话服务：选适配器、做能力校验、拼请求，并记录一次用量。
 *
 * <p>埋点是旁路：写流水/成本失败不影响对话本身（{@link UsageService#record} 内部吞掉成本异常）。</p>
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
        return chat(userId, appKeyRef, model, prompt, SOURCE_SYNC);
    }

    /** source 用于区分调用入口：SYNC / ASYNC（MQ 消费者） */
    public String chat(String userId, String appKeyRef, String model, String prompt, String source) {
        AiProviderAdapter adapter = registry.resolve(model);
        ChatRequest request = new ChatRequest(model, List.of(ChatMessage.user(prompt)), 0.7);
        long start = System.currentTimeMillis();
        try {
            String reply = adapter.chat(appKeyRef, request);
            usageService.record(userId, model, source, prompt, reply,
                    System.currentTimeMillis() - start, "SUCCESS", null);
            return reply;
        } catch (ProviderException e) {
            usageService.record(userId, model, source, prompt, null,
                    System.currentTimeMillis() - start, "FAILED", e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            usageService.record(userId, model, source, prompt, null,
                    System.currentTimeMillis() - start, "FAILED", e.getMessage());
            throw e;
        }
    }

    public void stream(String userId, String appKeyRef, String model, String prompt, ChunkSink downstream) {
        AiProviderAdapter adapter = registry.resolve(model);
        if (!adapter.capabilities().stream()) {
            throw new BizException(4003, "model 不支持流式输出: " + model);
        }
        ChatRequest request = new ChatRequest(model, List.of(ChatMessage.user(prompt)), 0.7);
        long start = System.currentTimeMillis();
        StringBuilder reply = new StringBuilder();

        ChunkSink accumulator = new ChunkSink() {
            @Override
            public void onChunk(ChatChunk chunk) {
                if (!chunk.finished() && !chunk.reasoning()) {
                    reply.append(chunk.delta());
                }
                downstream.onChunk(chunk);
            }

            @Override
            public boolean cancelled() {
                return downstream.cancelled();
            }
        };

        try {
            adapter.stream(appKeyRef, request, accumulator);
            usageService.record(userId, model, SOURCE_STREAM, prompt, reply.toString(),
                    System.currentTimeMillis() - start, "SUCCESS", null);
        } catch (StreamCancelledException e) {
            // 客户端断开：不计费，但留一条 CANCELLED 流水便于统计中断率
            usageService.record(userId, model, SOURCE_STREAM, prompt, reply.toString(),
                    System.currentTimeMillis() - start, "CANCELLED", "client disconnected");
            throw e;
        } catch (ProviderException e) {
            usageService.record(userId, model, SOURCE_STREAM, prompt, reply.toString(),
                    System.currentTimeMillis() - start, "FAILED", e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            usageService.record(userId, model, SOURCE_STREAM, prompt, reply.toString(),
                    System.currentTimeMillis() - start, "FAILED", e.getMessage());
            throw e;
        }
    }
}
