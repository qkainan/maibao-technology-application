package com.maibao.tech.workflow;

import com.maibao.tech.chat.ChatService;

/** 调用大模型的节点：把上一个节点的输出当 prompt，结果写回上下文。 */
public class LlmNode implements Node {

    private final String nodeName;
    private final String model;
    private final String promptVar;
    private final String outputVar;
    private final ChatService chatService;

    public LlmNode(String nodeName, String model, String promptVar, String outputVar, ChatService chatService) {
        this.nodeName = nodeName;
        this.model = model;
        this.promptVar = promptVar;
        this.outputVar = outputVar;
        this.chatService = chatService;
    }

    @Override
    public String name() {
        return nodeName;
    }

    @Override
    public void execute(NodeContext ctx) {
        String prompt = ctx.getString(promptVar);
        // 工作流里的调用统一记在 "workflow" 名下，便于和用户对话分开统计
        String reply = chatService.chat("workflow", "", model, prompt == null ? "" : prompt);
        ctx.set(outputVar, reply);
    }
}
