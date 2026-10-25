package com.maibao.tech.chat.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.maibao.tech.adapter.domain.ChatChunk;

/**
 * 把 OpenAI 兼容协议的 SSE 行解码成统一的 {@link ChatChunk}。
 *
 * <p>有状态：内部持有帧缓冲，因此<b>每次流式调用都要新建一个</b>，不能做成单例 Bean。</p>
 *
 * <p>它同时承担两件事：按空行切帧（{@link SseFrameParser}）与多厂商字段归一
 * （{@link ChunkNormalizer}）。这样"粘包/半包"和"厂商差异"都被挡在适配器内部。</p>
 */
public class OpenAiSseDecoder {

    private static final String DONE = "[DONE]";

    private final SseFrameParser parser = new SseFrameParser();
    private final ChunkNormalizer normalizer;
    private final ObjectMapper objectMapper;

    public OpenAiSseDecoder(ChunkNormalizer normalizer, ObjectMapper objectMapper) {
        this.normalizer = normalizer;
        this.objectMapper = objectMapper;
    }

    /** 喂一行（不含换行符），返回本次能解出的增量，可能为空。 */
    @SuppressWarnings("unchecked")
    public List<ChatChunk> feedLine(String line) {
        List<ChatChunk> chunks = new ArrayList<>();
        for (String frame : parser.feed(line + "\n")) {
            String data = SseFrameParser.dataOf(frame).trim();
            if (data.isEmpty()) {
                continue;
            }
            if (DONE.equals(data)) {
                chunks.add(ChatChunk.done());
                continue;
            }
            try {
                Map<String, Object> raw = objectMapper.readValue(data, Map.class);
                ChatChunk chunk = normalizer.normalize(raw);
                if (chunk.finished() || !chunk.delta().isEmpty()) {
                    chunks.add(chunk);
                }
            } catch (JsonProcessingException e) {
                // 厂商偶发发送非 JSON 的心跳/注释行，跳过即可，不要把整条流打断
            }
        }
        return chunks;
    }
}
