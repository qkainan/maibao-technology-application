package com.maibao.tech.chat.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.maibao.tech.adapter.ChatChunk;

class ChunkNormalizerTest {

    private final ChunkNormalizer normalizer = new ChunkNormalizer();

    @Test
    void should_normalize_openai_delta() {
        Map<String, Object> raw = Map.of("choices", List.of(Map.of("delta", Map.of("content", "你"))));
        ChatChunk chunk = normalizer.normalize(raw);
        assertThat(chunk.delta()).isEqualTo("你");
        assertThat(chunk.reasoning()).isFalse();
    }

    @Test
    void should_normalize_reasoning_content() {
        Map<String, Object> raw = Map.of("choices", List.of(Map.of("delta", Map.of("reasoning_content", "思考"))));
        ChatChunk chunk = normalizer.normalize(raw);
        assertThat(chunk.reasoning()).isTrue();
        assertThat(chunk.delta()).isEqualTo("思考");
    }

    @Test
    void should_normalize_plain_result_field() {
        ChatChunk chunk = normalizer.normalize(Map.of("result", "好"));
        assertThat(chunk.delta()).isEqualTo("好");
    }
}
