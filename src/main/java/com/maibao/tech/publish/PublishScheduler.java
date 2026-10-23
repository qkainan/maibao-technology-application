package com.maibao.tech.publish;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 到点扫描。真实项目用 XXL-Job，这里用 Spring 定时任务等价复刻"执行器"角色：
 * 定时只是触发器，不是状态源。
 */
@Component
public class PublishScheduler {

    private final PublishService publishService;

    public PublishScheduler(PublishService publishService) {
        this.publishService = publishService;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void scan() {
        publishService.scanAndClaimDue();
    }
}
