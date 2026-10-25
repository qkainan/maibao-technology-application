package com.maibao.tech.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.maibao.tech.chat.ChatService;
import com.maibao.tech.mq.domain.TaskMessage;

/**
 * 异步任务的真实业务动作：调一次模型对话，并记录用量与成本。
 *
 * <p>幂等锚点先提交（挡住重复投递），再调外部模型；失败时撤销锚点并抛出，
 * 让消费者按重试策略处理。这样数据库事务不会跨越网络调用。</p>
 *
 * <p>成本由 {@link ChatService} 统一记账（source=ASYNC），所以异步任务也会出现在
 * 用量与成本统计里。</p>
 */
@ConditionalOnProperty(name = "app.task.enabled", havingValue = "true")
@Service
public class TaskProcessor {

    private static final Logger log = LoggerFactory.getLogger(TaskProcessor.class);

    private final TaskDedupeService dedupe;
    private final ChatService chatService;
    private final String defaultModel;
    private final String defaultUserId;

    public TaskProcessor(TaskDedupeService dedupe,
                         ChatService chatService,
                         @Value("${app.task.model:mock:echo}") String defaultModel,
                         @Value("${app.task.user-id:async-task}") String defaultUserId) {
        this.dedupe = dedupe;
        this.chatService = chatService;
        this.defaultModel = defaultModel;
        this.defaultUserId = defaultUserId;
    }

    public void process(TaskMessage message) {
        dedupe.markProcessed(message.taskId());
        String model = blankToDefault(message.model(), defaultModel);
        String userId = blankToDefault(message.userId(), defaultUserId);
        try {
            String reply = chatService.chat(userId, "", model, message.payload(), "ASYNC");
            log.info("[mq] task {} done: model={} user={} replyLen={}",
                    message.taskId(), model, userId, reply.length());
        } catch (RuntimeException e) {
            unmarkQuietly(message.taskId());
            throw e;
        }
    }

    private void unmarkQuietly(String taskId) {
        try {
            dedupe.unmark(taskId);
        } catch (RuntimeException e) {
            // 撤销失败意味着重试会被当成重复消息跳过，必须留下明确日志
            log.error("failed to unmark processed task {}, retry may be skipped: {}", taskId, e.toString());
        }
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
