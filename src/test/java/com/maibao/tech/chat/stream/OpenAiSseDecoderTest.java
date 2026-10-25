package com.maibao.tech.chat.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.maibao.tech.adapter.domain.ChatChunk;

class OpenAiSseDecoderTest {

    private final OpenAiSseDecoder decoder = new OpenAiSseDecoder(new ChunkNormalizer(), new ObjectMapper());

    @Test
    void should_decode_frame_after_blank_line() {
        // 半包：第一行到齐但还没有空行，不能解出帧
        assertThat(decoder.feedLine("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}")).isEmpty();
        List<ChatChunk> chunks = decoder.feedLine("");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).delta()).isEqualTo("你");
    }

    @Test
    void should_emit_done_on_sentinel() {
        decoder.feedLine("data: [DONE]");
        List<ChatChunk> chunks = decoder.feedLine("");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).finished()).isTrue();
    }

    @Test
    void should_decode_reasoning_content() {
        decoder.feedLine("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"想想\"}}]}");
        List<ChatChunk> chunks = decoder.feedLine("");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).reasoning()).isTrue();
        assertThat(chunks.get(0).delta()).isEqualTo("想想");
    }

    @Test
    void should_ignore_comment_and_malformed_payload() {
        assertThat(decoder.feedLine(": keep-alive")).isEmpty();
        decoder.feedLine("data: not-json");
        assertThat(decoder.feedLine("")).isEmpty();
    }
}
