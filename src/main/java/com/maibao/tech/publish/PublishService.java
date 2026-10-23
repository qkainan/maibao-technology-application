package com.maibao.tech.publish;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.maibao.tech.common.BizException;

@Service
public class PublishService {

    private static final Logger log = LoggerFactory.getLogger(PublishService.class);

    private final PublishRepository repository;

    public PublishService(PublishRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public PublishRecord create(String nodeId, Instant scheduledAt) {
        return repository.save(new PublishRecord(nodeId, scheduledAt));
    }

    public PublishRecord get(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(4041, "publish record not found"));
    }

    /** 抢占：SCHEDULED -> PUBLISHING，只有一行能成功 */
    public boolean claim(Long id) {
        int updated = repository.casStatus(id, PublishStatus.SCHEDULED, PublishStatus.PUBLISHING);
        return updated == 1;
    }

    public void finish(Long id, boolean success, String reason) {
        repository.casStatusWithReason(id, PublishStatus.PUBLISHING,
                success ? PublishStatus.SUCCESS : PublishStatus.FAILED,
                success ? null : truncate(reason));
    }

    public boolean cancel(Long id) {
        return repository.casStatusWithReason(id, PublishStatus.SCHEDULED,
                PublishStatus.CANCELLED, "cancelled by user") == 1;
    }

    /** 到期扫描：先查再抢，抢不到说明别人在跑 */
    public int scanAndClaimDue() {
        List<PublishRecord> due = repository.findByStatusAndScheduledAtLessThanEqual(
                PublishStatus.SCHEDULED, Instant.now());
        int claimed = 0;
        for (PublishRecord r : due) {
            if (claim(r.getId())) {
                claimed++;
                log.info("claimed due publish {}", r.getId());
            }
        }
        return claimed;
    }

    /**
     * 并发演示：N 个线程同时抢同一条记录，正确结果是有且只有 1 个成功。
     * 用它来验证乐观锁真的挡住了并发。
     */
    public int race(Long id, int threads) {
        // 先复位成 SCHEDULED，保证同一条记录可以反复演示
        repository.forceStatus(id, PublishStatus.SCHEDULED);
        publishLog(id);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (claim(id)) {
                        success.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdown();
        return success.get();
    }

    private void publishLog(Long id) {
        log.info("race demo on publish {}, current status = {}", id, get(id).getStatus());
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 255 ? s.substring(0, 255) : s;
    }
}
