package com.maibao.tech.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.maibao.tech.common.domain.ApiResponse;
import com.maibao.tech.mq.OutboxService;
import com.maibao.tech.mq.domain.TaskMessage;

/**
 * 提交异步任务：只写 outbox，由投递器负责真正发消息。
 *
 * <p>接口返回 taskId 就代表"业务已受理"——消息晚一点到，但不会丢。</p>
 */
@RestController
@RequestMapping("/api/task")
public class TaskController {

    private final OutboxService outboxService;

    public TaskController(OutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @PostMapping("/submit")
    public ApiResponse<String> submit(@RequestParam String payload,
                                      @RequestParam(required = false) String taskId) {
        String id = taskId == null ? UUID.randomUUID().toString() : taskId;
        outboxService.enqueue(TaskMessage.of(id, payload));
        return ApiResponse.ok(id);
    }
}
