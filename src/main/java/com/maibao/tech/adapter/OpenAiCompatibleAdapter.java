package com.maibao.tech.adapter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.maibao.tech.adapter.domain.Capabilities;
import com.maibao.tech.adapter.domain.ChatChunk;
import com.maibao.tech.adapter.domain.ChatRequest;
import com.maibao.tech.adapter.domain.ProviderError;
import com.maibao.tech.adapter.domain.ProviderException;
import com.maibao.tech.chat.stream.ChunkNormalizer;
import com.maibao.tech.chat.stream.OpenAiSseDecoder;
import com.maibao.tech.common.BizException;
import com.maibao.tech.security.KeyVaultService;

/**
 * OpenAI 兼容协议的适配器：入参翻译、凭证获取、真实流式读取、错误码归一。
 *
 * <p>流式实现直接读上游字节流，按行喂给 {@link OpenAiSseDecoder}（内部做帧切分 +
 * 字段归一）；客户端断开时通过 {@link ChunkSink#cancelled()} 尽快停止读取并关闭连接。</p>
 *
 * <p>超时分层：连接超时在建 Client 时设定；首字节超时用请求级 timeout；
 * 整体超时在读循环里按 deadline 判断。</p>
 */
@Component
public class OpenAiCompatibleAdapter implements AiProviderAdapter {

    private final HttpClient httpClient;
    private final String baseUrl;
    private final KeyVaultService keyVaultService;
    private final String configuredApiKey;
    private final ObjectMapper objectMapper;
    private final ChunkNormalizer chunkNormalizer;
    private final long firstByteTimeoutMs;
    private final long overallTimeoutMs;

    public OpenAiCompatibleAdapter(KeyVaultService keyVaultService,
                                   ObjectMapper objectMapper,
                                   ChunkNormalizer chunkNormalizer,
                                   @Value("${app.provider.openai.base-url}") String baseUrl,
                                   @Value("${app.provider.openai.api-key:}") String configuredApiKey,
                                   @Value("${app.provider.openai.connect-timeout-ms:5000}") long connectTimeoutMs,
                                   @Value("${app.provider.openai.first-byte-timeout-ms:30000}") long firstByteTimeoutMs,
                                   @Value("${app.provider.openai.overall-timeout-ms:300000}") long overallTimeoutMs) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.keyVaultService = keyVaultService;
        this.objectMapper = objectMapper;
        this.chunkNormalizer = chunkNormalizer;
        this.configuredApiKey = configuredApiKey;
        this.firstByteTimeoutMs = firstByteTimeoutMs;
        this.overallTimeoutMs = overallTimeoutMs;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public Capabilities capabilities() {
        // vision / toolCall 目前没有实现，如实声明为 false
        return new Capabilities(true, false, false, 128000);
    }

    @Override
    @SuppressWarnings("unchecked")
    public String chat(String appKeyRef, ChatRequest request) {
        HttpRequest httpRequest = newRequest(resolveApiKey(appKeyRef), body(request, false))
                .timeout(Duration.ofMillis(overallTimeoutMs))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ensureOk(response.statusCode(), response.body());
            Map<String, Object> json = objectMapper.readValue(response.body(), Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) json.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new ProviderException(ProviderError.SERVER, "provider returned no choices");
            }
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            return String.valueOf(message.get("content"));
        } catch (HttpTimeoutException e) {
            throw new ProviderException(ProviderError.TIMEOUT, "provider timeout after " + overallTimeoutMs + "ms", e);
        } catch (IOException e) {
            throw new ProviderException(ProviderError.SERVER, "provider io error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(ProviderError.UNKNOWN, "interrupted", e);
        }
    }

    @Override
    public void stream(String appKeyRef, ChatRequest request, ChunkSink sink) {
        HttpRequest httpRequest = newRequest(resolveApiKey(appKeyRef), body(request, true))
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofMillis(firstByteTimeoutMs))
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new ProviderException(ProviderError.TIMEOUT,
                    "no first byte within " + firstByteTimeoutMs + "ms", e);
        } catch (IOException e) {
            throw new ProviderException(ProviderError.SERVER, "provider io error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(ProviderError.UNKNOWN, "interrupted", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ProviderException(mapStatus(response.statusCode()),
                    "provider http " + response.statusCode() + ": " + readErrorBody(response.body()));
        }

        OpenAiSseDecoder decoder = new OpenAiSseDecoder(chunkNormalizer, objectMapper);
        long deadline = System.currentTimeMillis() + overallTimeoutMs;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sink.cancelled()) {
                    throw new StreamCancelledException();
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new ProviderException(ProviderError.TIMEOUT,
                            "stream exceeded overall timeout " + overallTimeoutMs + "ms");
                }
                for (ChatChunk chunk : decoder.feedLine(line)) {
                    sink.onChunk(chunk);
                }
            }
        } catch (IOException e) {
            if (sink.cancelled()) {
                throw new StreamCancelledException();
            }
            throw new ProviderException(ProviderError.SERVER, "stream read failed: " + e.getMessage(), e);
        }
    }

    private String resolveApiKey(String appKeyRef) {
        String apiKey = keyVaultService.resolve(appKeyRef);
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = configuredApiKey;
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException(4001, "no api key: 既没有 appKeyRef，也没有配置 app.provider.openai.api-key");
        }
        return apiKey;
    }

    private Map<String, Object> body(ChatRequest request, boolean stream) {
        List<Map<String, String>> messages = request.messages().stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("messages", messages);
        body.put("temperature", request.temperature());
        if (stream) {
            body.put("stream", true);
        }
        return body;
    }

    private HttpRequest.Builder newRequest(String apiKey, Map<String, Object> body) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new ProviderException(ProviderError.UNKNOWN, "cannot serialize provider request", e);
        }
        return HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
    }

    private void ensureOk(int status, String body) {
        if (status >= 200 && status < 300) {
            return;
        }
        throw new ProviderException(mapStatus(status), "provider http " + status + ": " + abbreviate(body));
    }

    private ProviderError mapStatus(int status) {
        return switch (status) {
            case 401, 403 -> ProviderError.AUTH;
            case 429 -> ProviderError.RATE_LIMIT;
            case 400, 422 -> ProviderError.FILTERED;
            default -> status >= 500 ? ProviderError.SERVER : ProviderError.UNKNOWN;
        };
    }

    private String readErrorBody(InputStream body) {
        try (InputStream in = body) {
            return abbreviate(new String(in.readNBytes(512), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return "";
        }
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
}
