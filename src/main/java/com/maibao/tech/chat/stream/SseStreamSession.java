package com.maibao.tech.chat.stream;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.maibao.tech.adapter.ChunkSink;
import com.maibao.tech.adapter.StreamCancelledException;
import com.maibao.tech.adapter.domain.ChatChunk;

/**
 * 一条 SSE 连接的状态：事件序号、心跳、取消标记、线程安全的发送。
 *
 * <p>为什么需要它：{@code SseEmitter} 允许多线程写，但并发 send 会打乱帧边界；
 * 这里用一把锁把"增量"和"心跳"串起来，同时用 {@code cancelled} 把客户端断开
 * 传播回适配器。</p>
 */
public class SseStreamSession {

    private final SseEmitter emitter;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicLong sequence = new AtomicLong();
    private final Object sendLock = new Object();

    public SseStreamSession(long timeoutMs) {
        this.emitter = new SseEmitter(timeoutMs);
        this.emitter.onCompletion(() -> cancelled.set(true));
        this.emitter.onTimeout(() -> cancelled.set(true));
        this.emitter.onError(e -> cancelled.set(true));
    }

    public SseEmitter emitter() {
        return emitter;
    }

    public boolean cancelled() {
        return cancelled.get();
    }

    /** 已发出的事件数，便于排查"前端收到几帧"。 */
    public long sequence() {
        return sequence.get();
    }

    /** 交给适配器的写端。 */
    public ChunkSink sink() {
        return new ChunkSink() {
            @Override
            public void onChunk(ChatChunk chunk) {
                sendChunk(chunk);
            }

            @Override
            public boolean cancelled() {
                return cancelled.get();
            }
        };
    }

    public void sendChunk(ChatChunk chunk) {
        String event = chunk.finished() ? "done" : (chunk.reasoning() ? "reasoning" : "text");
        send(event, chunk.delta());
    }

    /** 每个事件带自增 id，前端可用于去重/排查。 */
    public void send(String event, String data) {
        long id = sequence.incrementAndGet();
        try {
            synchronized (sendLock) {
                emitter.send(SseEmitter.event().id(Long.toString(id)).name(event).data(data));
            }
        } catch (IOException | IllegalStateException e) {
            cancelled.set(true);
            throw new StreamCancelledException();
        }
    }

    /** 每 15s 一个注释帧，避免网关把空闲连接判死。 */
    public void heartbeat() {
        if (cancelled.get()) {
            return;
        }
        try {
            synchronized (sendLock) {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            }
        } catch (IOException | IllegalStateException e) {
            cancelled.set(true);
        }
    }

    public void complete() {
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // 客户端已断开，忽略
        }
    }

    /** 流式过程中出错：先补一个 failed 事件，再结束。 */
    public void fail(String code, String message) {
        try {
            send("failed", "{\"code\":\"" + escape(code) + "\",\"message\":\"" + escape(message) + "\"}");
        } catch (RuntimeException ignored) {
            // 已断开
        } finally {
            complete();
        }
    }

    private String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
