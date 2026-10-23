package com.maibao.tech.chat.stream;

import java.util.ArrayList;
import java.util.List;

/**
 * SSE 帧解析器：把字节流切成一个个事件帧。
 *
 * <p>为什么要自己写：HTTP 的传输分片不等于事件边界。一次 read 可能拿到半个事件，
 * 也可能拿到两个半（粘包 / 半包），必须自己攒 buffer 按空行切。</p>
 */
public class SseFrameParser {

    private final StringBuilder buffer = new StringBuilder();

    /** 喂一段原始文本，返回本次能完整切出的帧（可能为空，也可能多个）。 */
    public List<String> feed(String chunk) {
        buffer.append(chunk);
        List<String> frames = new ArrayList<>();
        int idx;
        while ((idx = buffer.indexOf("\n\n")) >= 0) {
            frames.add(buffer.substring(0, idx));
            buffer.delete(0, idx + 2);
        }
        return frames;
    }

    /** 从一帧里取出 data 字段（多行 data 用换行拼接），跳过事件名与注释。 */
    public static String dataOf(String frame) {
        StringBuilder sb = new StringBuilder();
        for (String line : frame.split("\n")) {
            if (line.startsWith("data:")) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line.substring(5).stripLeading());
            }
        }
        return sb.toString();
    }

    public String remaining() {
        return buffer.toString();
    }
}
