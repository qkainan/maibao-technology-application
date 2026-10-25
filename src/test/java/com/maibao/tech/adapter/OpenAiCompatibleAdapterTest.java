package com.maibao.tech.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import com.maibao.tech.adapter.domain.ChatChunk;
import com.maibao.tech.adapter.domain.ChatMessage;
import com.maibao.tech.adapter.domain.ChatRequest;
import com.maibao.tech.adapter.domain.ProviderError;
import com.maibao.tech.adapter.domain.ProviderException;
import com.maibao.tech.chat.stream.ChunkNormalizer;
import com.maibao.tech.security.KeyVaultService;

/**
 * 用 JDK 自带的 HttpServer 起一个假厂商，验证：错误码归一、真实流式解析、取消传播。
 * 不依赖外网，也不占用固定端口。
 */
class OpenAiCompatibleAdapterTest {

    private HttpServer server;
    private String baseUrl;
    private volatile int status = 200;
    private volatile String sseBody = "";
    private volatile boolean slowSse;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body;
            if (sseBody.isEmpty()) {
                body = "{}".getBytes(StandardCharsets.UTF_8);
            } else {
                body = sseBody.getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", sseBody.isEmpty() ? "application/json" : "text/event-stream");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private OpenAiCompatibleAdapter adapter() {
        KeyVaultService keyVault = mock(KeyVaultService.class);
        when(keyVault.resolve(null)).thenReturn(null);
        return new OpenAiCompatibleAdapter(keyVault, new ObjectMapper(), new ChunkNormalizer(),
                baseUrl, "test-key", 2000, 2000, 5000);
    }

    private ChatRequest request() {
        return new ChatRequest("openai:test", List.of(ChatMessage.user("hi")), 0.7);
    }

    @Test
    void should_map_401_to_auth() {
        status = 401;
        assertThatThrownBy(() -> adapter().chat(null, request()))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).error()).isEqualTo(ProviderError.AUTH));
    }

    @Test
    void should_map_429_to_rate_limit() {
        status = 429;
        assertThatThrownBy(() -> adapter().chat(null, request()))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).error()).isEqualTo(ProviderError.RATE_LIMIT));
    }

    @Test
    void should_map_500_to_server() {
        status = 500;
        assertThatThrownBy(() -> adapter().chat(null, request()))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).error()).isEqualTo(ProviderError.SERVER));
    }

    @Test
    void should_stream_fragmented_frames_and_done() {
        sseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n"
                + "data: [DONE]\n\n";
        List<ChatChunk> chunks = new ArrayList<>();
        adapter().stream(null, request(), new ChunkSink() {
            @Override
            public void onChunk(ChatChunk chunk) {
                chunks.add(chunk);
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        });

        assertThat(chunks).extracting(ChatChunk::delta).containsExactly("你", "好", "");
        assertThat(chunks.get(chunks.size() - 1).finished()).isTrue();
    }

    @Test
    void should_stop_reading_when_sink_cancelled() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            body.append("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n");
        }
        body.append("data: [DONE]\n\n");
        sseBody = body.toString();

        AtomicInteger received = new AtomicInteger();
        assertThatThrownBy(() -> adapter().stream(null, request(), new ChunkSink() {
            @Override
            public void onChunk(ChatChunk chunk) {
                received.incrementAndGet();
            }

            @Override
            public boolean cancelled() {
                return received.get() >= 2;
            }
        })).isInstanceOf(StreamCancelledException.class);

        assertThat(received.get()).isLessThan(50);
    }
}
