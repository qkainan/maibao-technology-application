package com.maibao.tech.adapter;

import java.util.List;

/** 统一入参：上层只依赖这个结构，不关心是哪家模型。 */
public record ChatRequest(String model, List<ChatMessage> messages, double temperature) {
}
