package com.maibao.tech.publish;

import com.maibao.tech.publish.domain.PublishRecord;

/**
 * 发布动作本身。
 *
 * <p>状态机（抢占 / 收尾 / 回收）与「真正把东西发出去」是两件事：
 * 前者由 {@link PublishService} 负责，后者由实现类负责。这样测试里可以换上
 * 会抛异常的实现来验证失败路径，而不必在业务代码里埋 if。</p>
 */
public interface PublishHandler {

    void publish(PublishRecord record) throws Exception;
}
