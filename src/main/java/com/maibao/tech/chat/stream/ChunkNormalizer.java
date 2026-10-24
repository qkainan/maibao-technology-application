package com.maibao.tech.chat.stream;

import java.util.Map;

import org.springframework.stereotype.Component;
import com.maibao.tech.adapter.domain.ChatChunk;



/**
 * 增量归一：不同厂商的字段名与结构不同，这里统一成 ChatChunk。
 *
 * <ul>
 *   <li>OpenAI：choices[0].delta.content</li>
 *   <li>部分厂商：result</li>
 *   <li>思维链：reasoning_content</li>
 * </ul>
 */
@Component
public class ChunkNormalizer {

    @SuppressWarnings("unchecked")
    public ChatChunk normalize(Map<String, Object> raw) {
        if (raw == null) {
            return ChatChunk.done();
        }
        Object choicesObj = raw.get("choices");
        if (choicesObj instanceof java.util.List<?> choices && !choices.isEmpty()) {
            Map<String, Object> choice = (Map<String, Object>) choices.get(0);
            Object deltaObj = choice.get("delta");
            if (deltaObj instanceof Map<?, ?> delta) {
                Object reasoning = delta.get("reasoning_content");
                if (reasoning != null) {
                    return ChatChunk.reasoning(String.valueOf(reasoning));
                }
                Object content = delta.get("content");
                if (content != null) {
                    return ChatChunk.text(String.valueOf(content));
                }
            }
        }
        Object result = raw.get("result");
        if (result != null) {
            return ChatChunk.text(String.valueOf(result));
        }
        Object reasoning = raw.get("reasoning_content");
        if (reasoning != null) {
            return ChatChunk.reasoning(String.valueOf(reasoning));
        }
        return new ChatChunk("", false, Boolean.TRUE.equals(raw.get("finished")));
    }
}
