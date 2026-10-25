package com.maibao.tech.controller;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.maibao.tech.adapter.StreamCancelledException;
import com.maibao.tech.adapter.domain.ProviderException;
import com.maibao.tech.chat.ChatService;
import com.maibao.tech.chat.stream.SseStreamSession;
import com.maibao.tech.common.BizException;
import com.maibao.tech.common.domain.ApiResponse;
import com.maibao.tech.config.ExecutorConfig;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    /** 整体超时：防止僵尸流一直占着连接 */
    private static final long SSE_TIMEOUT_MS = 300_000L;
    private static final long HEARTBEAT_SECONDS = 15L;

    private final ChatService chatService;
    private final ExecutorService streamExecutor;
    private final ScheduledExecutorService heartbeatScheduler;

    public ChatController(ChatService chatService,
                          ExecutorConfig.StreamExecutorHolder holder,
                          @Qualifier("streamHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler) {
        this.chatService = chatService;
        this.streamExecutor = holder.executor();
        this.heartbeatScheduler = heartbeatScheduler;
    }

    @PostMapping
    public ApiResponse<String> chat(@RequestParam(defaultValue = "mock:echo") String model,
                                    @RequestParam(defaultValue = "") String appKeyRef,
                                    @RequestParam(defaultValue = "anonymous") String userId,
                                    @RequestParam String prompt) {
        return ApiResponse.ok(chatService.chat(userId, appKeyRef, model, prompt));
    }

    /**
     * SSE 流式：控制器只建连接，真正的读取在流式线程池里跑，容器线程立刻释放。
     *
     * <p>每个事件带自增 id；每 15s 一个 keep-alive 注释帧；客户端断开后
     * {@link SseStreamSession#cancelled()} 变 true，适配器据此停止读取上游。</p>
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(defaultValue = "mock:echo") String model,
                             @RequestParam(defaultValue = "") String appKeyRef,
                             @RequestParam(defaultValue = "anonymous") String userId,
                             @RequestParam String prompt) {
        SseStreamSession session = new SseStreamSession(SSE_TIMEOUT_MS);
        streamExecutor.execute(() -> {
            ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(
                    session::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
            try {
                chatService.stream(userId, appKeyRef, model, prompt, session.sink());
                session.complete();
            } catch (StreamCancelledException e) {
                // 客户端主动断开：静默收尾，不记故障
                session.complete();
            } catch (ProviderException e) {
                session.fail(e.error().name(), e.getMessage());
            } catch (BizException e) {
                session.fail("BIZ_" + e.getCode(), e.getMessage());
            } catch (RuntimeException e) {
                session.fail("SERVER", e.getMessage());
            } finally {
                heartbeat.cancel(false);
            }
        });
        return session.emitter();
    }
}
