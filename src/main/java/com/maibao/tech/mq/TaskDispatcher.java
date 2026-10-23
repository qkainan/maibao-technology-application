package com.maibao.tech.mq;

public interface TaskDispatcher {

    /** @return 是否走了真正的异步链路 */
    boolean dispatch(TaskMessage message);
}
