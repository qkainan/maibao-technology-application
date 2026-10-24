package com.maibao.tech.mq;

import com.maibao.tech.mq.domain.TaskMessage;

/**
 * 任务投递的唯一出口。
 *
 * <p>异步是设计的一部分，不存在「同步降级」分支：投递失败就抛 {@code AmqpException}，
 * 让调用方看到明确的错误，而不是悄悄改成同步执行、把可靠性问题藏起来。</p>
 */
public interface TaskDispatcher {

    void dispatch(TaskMessage message);
}
