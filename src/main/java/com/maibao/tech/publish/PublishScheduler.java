package com.maibao.tech.publish;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时触发器：只负责按节奏调用，不持有状态。
 *
 * <p>两个职责分开：scan 负责「到点的记录抢占并提交执行」，
 * recover 负责「把卡在 PUBLISHING 的记录标记为失败」。没有 recover，
 * 执行线程意外退出后记录会永久停在中间态。</p>
 */
@Component
public class PublishScheduler {

    private static final Logger log = LoggerFactory.getLogger(PublishScheduler.class);

    private final PublishService publishService;

    public PublishScheduler(PublishService publishService) {
        this.publishService = publishService;
    }

    @Scheduled(fixedDelayString = "${app.publish.scan-interval-ms:5000}", initialDelay = 5000)
    public void scan() {
        publishService.scanAndClaimDue();
    }

    @Scheduled(fixedDelayString = "${app.publish.recovery-interval-ms:30000}", initialDelay = 10000)
    public void recover() {
        int recovered = publishService.recoverStuck();
        if (recovered > 0) {
            log.warn("recovered {} stuck publish records", recovered);
        }
    }
}
