package com.maibao.tech.controller;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;


import com.maibao.tech.adapter.domain.ChatChunk;
import com.maibao.tech.chat.ChatService;

import com.maibao.tech.common.domain.ApiResponse;
import com.maibao.tech.config.ExecutorConfig;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final ExecutorService streamExecutor;

    public ChatController(ChatService chatService, ExecutorConfig.StreamExecutorHolder holder) {
        this.chatService = chatService;
        this.streamExecutor = holder.executor();
    }

    @PostMapping
    public ApiResponse<String> chat(@RequestParam(defaultValue = "mock:echo") String model,
                                    @RequestParam(defaultValue = "") String appKeyRef,
                                    @RequestParam String prompt) {
        return ApiResponse.ok(chatService.chat(appKeyRef, model, prompt));
    }

    /** SSE 流式：服务端把统一后的增量逐帧推出，事件名区分 text / reasoning / done */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(defaultValue = "mock:echo") String model,
                             @RequestParam(defaultValue = "") String appKeyRef,
                             @RequestParam String prompt) {
        SseEmitter emitter = new SseEmitter(0L);
        streamExecutor.execute(() -> {
            try {
                chatService.stream(appKeyRef, model, prompt, chunk -> emit(emitter, chunk));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private void emit(SseEmitter emitter, ChatChunk chunk) {
        try {
            String event = chunk.finished() ? "done" : (chunk.reasoning() ? "reasoning" : "text");
            emitter.send(SseEmitter.event().name(event).data(chunk.delta()));
        } catch (IOException e) {
            throw new IllegalStateException("client disconnected", e);
        }
    }
}
