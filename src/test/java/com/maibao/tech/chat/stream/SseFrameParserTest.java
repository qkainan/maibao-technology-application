package com.maibao.tech.chat.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class SseFrameParserTest {

    @Test
    void should_join_partial_frame() {
        SseFrameParser parser = new SseFrameParser();
        assertThat(parser.feed("data: he")).isEmpty();
        assertThat(parser.feed("llo\n\n")).containsExactly("data: hello");
        assertThat(SseFrameParser.dataOf("data: hello")).isEqualTo("hello");
    }

    @Test
    void should_split_multiple_frames_in_one_chunk() {
        SseFrameParser parser = new SseFrameParser();
        List<String> frames = parser.feed("data: a\n\ndata: b\n\ndata: par");
        assertThat(frames).containsExactly("data: a", "data: b");
        assertThat(parser.remaining()).isEqualTo("data: par");
    }

    @Test
    void should_ignore_event_and_comment_lines() {
        SseFrameParser parser = new SseFrameParser();
        List<String> frames = parser.feed(": keep-alive\nevent: text\ndata: hi\n\n");
        assertThat(frames).hasSize(1);
        assertThat(SseFrameParser.dataOf(frames.get(0))).isEqualTo("hi");
    }
}
