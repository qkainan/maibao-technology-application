package com.maibao.tech.usage.domain.vo;

import java.math.BigDecimal;
import java.time.Instant;
import com.maibao.tech.usage.domain.po.UserQueryLog;

/** 对话流水的一条视图。 */
public record QueryLogView(Long id,
                           String requestId,
                           String userId,
                           String model,
                           String source,
                           String prompt,
                           String reply,
                           int promptTokens,
                           int completionTokens,
                           int totalTokens,
                           BigDecimal costAmount,
                           int latencyMs,
                           String status,
                           String errorMsg,
                           Instant createdAt) {

    public static QueryLogView of(UserQueryLog log) {
        return new QueryLogView(log.getId(), log.getRequestId(), log.getUserId(), log.getModel(),
                log.getSource(), log.getPrompt(), log.getReply(), log.getPromptTokens(),
                log.getCompletionTokens(), log.getTotalTokens(), log.getCostAmount(),
                log.getLatencyMs(), log.getStatus(), log.getErrorMsg(), log.getCreatedAt());
    }
}
