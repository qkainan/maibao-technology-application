package com.maibao.tech.adapter;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.maibao.tech.adapter.domain.ChatChunk;
import com.maibao.tech.adapter.domain.ChatRequest;
import com.maibao.tech.common.BizException;
import com.maibao.tech.security.KeyVaultService;

/**
 * OpenAI 兼容协议的适配器实现：把内部 ChatRequest 翻译成 /chat/completions 的 body。
 *
 * <p>说明：当前 stream 实现先取回整段响应，再切块推给下游。逐 token 转发与帧解析由
 * {@link com.maibao.tech.chat.stream.SseFrameParser} 承担，目前独立实现并单测覆盖。</p>
 */
@Component
public class OpenAiCompatibleAdapter implements AiProviderAdapter {

    private final RestClient restClient;
    private final KeyVaultService keyVaultService;
    private final String configuredApiKey;

    public OpenAiCompatibleAdapter(RestClient.Builder builder,
                                   KeyVaultService keyVaultService,
                                   @Value("${app.provider.openai.base-url}") String baseUrl,
                                   @Value("${app.provider.openai.api-key:}") String configuredApiKey) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.keyVaultService = keyVaultService;
        this.configuredApiKey = configuredApiKey;
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    @SuppressWarnings("unchecked")
    public String chat(String appKeyRef, ChatRequest request) {
        String apiKey = keyVaultService.resolve(appKeyRef);
        if (apiKey == null || apiKey.isBlank()) {
            // 没有传 appKeyRef 时回落到 yml / 环境变量里的配置
            apiKey = configuredApiKey;
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException(4001, "no api key: 既没有 appKeyRef，也没有配置 app.provider.openai.api-key");
        }
        List<Map<String, String>> messages = request.messages().stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList();
        Map<String, Object> body = Map.of(
                "model", request.model(),
                "messages", messages,
                "temperature", request.temperature());

        Map<String, Object> resp = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .body(body)
                .retrieve()
                .body(Map.class);
        if (resp == null) {
            throw new BizException(5001, "empty response from provider");
        }
        List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return String.valueOf(message.get("content"));
    }

    @Override
    public void stream(String appKeyRef, ChatRequest request, Consumer<ChatChunk> sink) {
        String full = chat(appKeyRef, request);
        for (char c : full.toCharArray()) {
            sink.accept(ChatChunk.text(String.valueOf(c)));
        }
        sink.accept(ChatChunk.done());
    }
}
