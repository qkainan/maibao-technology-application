package com.maibao.tech.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.maibao.tech.chat.ChatService;

import com.maibao.tech.common.domain.ApiResponse;
import com.maibao.tech.workflow.GraphValidator;
import com.maibao.tech.workflow.LlmNode;
import com.maibao.tech.workflow.LoopNode;
import com.maibao.tech.workflow.Node;
import com.maibao.tech.workflow.NodeContext;
import com.maibao.tech.workflow.WorkflowEngine;

@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {

    private final WorkflowEngine engine;
    private final GraphValidator graphValidator;
    private final ChatService chatService;

    public WorkflowController(WorkflowEngine engine, GraphValidator graphValidator, ChatService chatService) {
        this.engine = engine;
        this.graphValidator = graphValidator;
        this.chatService = chatService;
    }

    /** 跑一个最小工作流：LLM 节点 + 循环 3 轮后停止 */
    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody(required = false) Map<String, String> body) {
        String prompt = body == null ? "介绍一下你自己" : body.getOrDefault("prompt", "介绍一下你自己");
        NodeContext ctx = new NodeContext();
        ctx.set("input", prompt);

        Node llm = new LlmNode("llm-1", "mock:echo", "input", "output", chatService);
        // 循环体：每轮调用一次 llm；当轮次变量 >= 3 时结束
        LoopNode loop = new LoopNode("loop-1", "round", 100, c -> c.getInt("round", 0) >= 3, List.of(llm));

        engine.run(List.of(loop), ctx);
        return ApiResponse.ok(ctx.snapshot());
    }

    /** 图校验：传入邻接表，返回处于环上的边 */
    @PostMapping("/validate")
    public ApiResponse<List<String>> validate(@RequestBody Map<String, List<String>> adj) {
        return ApiResponse.ok(graphValidator.findCycleEdges(adj));
    }
}
