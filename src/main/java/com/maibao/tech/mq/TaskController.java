package com.maibao.tech.mq;

import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.maibao.tech.common.ApiResponse;

@RestController
@RequestMapping("/api/task")
public class TaskController {

    private final TaskDispatcher dispatcher;

    public TaskController(TaskDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @PostMapping("/submit")
    public ApiResponse<String> submit(@RequestParam String payload,
                                      @RequestParam(required = false) String taskId) {
        String id = taskId == null ? UUID.randomUUID().toString() : taskId;
        boolean async = dispatcher.dispatch(TaskMessage.of(id, payload));
        return ApiResponse.ok(id + (async ? " (async via mq)" : " (inline, mq disabled)"));
    }
}
