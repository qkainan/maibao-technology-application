package com.maibao.tech.publish;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 默认的发布实现：记录日志并模拟耗时。
 *
 * <p>真实场景这里会调用发布接口或下发配置；本项目没有下游，所以用固定耗时占位，
 * 让「异步执行 + 收尾 + 超时回收」这条链路可以被完整观察到。</p>
 */
@Component
public class SimulatedPublishHandler implements PublishHandler {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPublishHandler.class);

    private final long durationMs;

    public SimulatedPublishHandler(@Value("${app.publish.work-duration-ms:200}") long durationMs) {
        this.durationMs = durationMs;
    }

    @Override
    public void publish(PublishRecord record) throws Exception {
        log.info("publishing node {} (publish id {})", record.getNodeId(), record.getId());
        Thread.sleep(durationMs);
    }
}
