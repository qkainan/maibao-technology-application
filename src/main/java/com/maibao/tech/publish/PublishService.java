package com.maibao.tech.publish;

import com.maibao.tech.publish.repository.PublishRepository;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.maibao.tech.common.BizException;
import com.maibao.tech.publish.domain.PublishRecord;
import com.maibao.tech.publish.domain.PublishStatus;

/**
 * 发布状态机：SCHEDULED → PUBLISHING → SUCCESS / FAILED。
 *
 * <p>状态推进一律走条件更新，所以「执行线程收尾」和「超时回收」即使同时发生，
 * 也只有一方能改成功（另一方影响行数为 0）。</p>
 */
@Service
public class PublishService {

    private static final Logger log = LoggerFactory.getLogger(PublishService.class);

    private final PublishRepository repository;
    private final PublishExecutor executor;
    private final PublishHandler handler;
    private final long stuckTimeoutSeconds;

    public PublishService(PublishRepository repository,
                          PublishExecutor executor,
                          PublishHandler handler,
                          @Value("${app.publish.stuck-timeout-seconds:60}") long stuckTimeoutSeconds) {
        this.repository = repository;
        this.executor = executor;
        this.handler = handler;
        this.stuckTimeoutSeconds = stuckTimeoutSeconds;
    }

    @Transactional
    public PublishRecord create(String nodeId, Instant scheduledAt) {
        return repository.save(new PublishRecord(nodeId, scheduledAt));
    }

    public PublishRecord get(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(4041, "publish record not found"));
    }

    /** 抢占：SCHEDULED -> PUBLISHING，只有一行能成功，并记下抢占时刻 */
    public boolean claim(Long id) {
        int updated = repository.claim(id, PublishStatus.SCHEDULED, PublishStatus.PUBLISHING, Instant.now());
        return updated == 1;
    }

    /**
     * 收尾：只有仍在 PUBLISHING 的记录能被推进。
     *
     * @return false 表示状态已被别人改过（重复收尾、或已被超时回收）
     */
    public boolean finish(Long id, boolean success, String reason) {
        int updated = repository.casStatusWithReason(id, PublishStatus.PUBLISHING,
                success ? PublishStatus.SUCCESS : PublishStatus.FAILED,
                success ? null : truncate(reason));
        if (updated == 0) {
            log.warn("finish ignored: publish {} 已不在 PUBLISHING", id);
        }
        return updated == 1;
    }

    public boolean cancel(Long id) {
        return repository.casStatusWithReason(id, PublishStatus.SCHEDULED,
                PublishStatus.CANCELLED, "cancelled by user") == 1;
    }

    /** 到期扫描：抢占后提交执行；抢不到说明别人在跑 */
    public int scanAndClaimDue() {
        List<PublishRecord> due = repository.findByStatusAndScheduledAtLessThanEqual(
                PublishStatus.SCHEDULED, Instant.now());
        int claimed = 0;
        for (PublishRecord r : due) {
            if (claim(r.getId())) {
                claimed++;
                log.info("claimed due publish {}", r.getId());
                executor.submit(() -> execute(r.getId()));
            }
        }
        return claimed;
    }

    /**
     * 执行单个发布：handler 成功则推进 SUCCESS，抛异常则推进 FAILED。
     *
     * <p>执行线程只负责「跑完 + 收尾」，所有状态推进仍是条件更新，
     * 因此不会和超时回收抢出两个终态。</p>
     */
    void execute(Long id) {
        long start = System.currentTimeMillis();
        try {
            handler.publish(get(id));
            boolean ok = finish(id, true, null);
            log.info("publish {} finished in {} ms (state advanced: {})",
                    id, System.currentTimeMillis() - start, ok);
        } catch (Exception e) {
            log.warn("publish {} failed in {} ms: {}",
                    id, System.currentTimeMillis() - start, e.toString());
            finish(id, false, e.getMessage());
        }
    }

    /**
     * 卡死回收：停留在 PUBLISHING 超过阈值的记录标记为 FAILED。
     *
     * <p>没有这一步，执行线程被 kill 或进程重启后，记录会永久卡在 PUBLISHING。</p>
     */
    public int recoverStuck() {
        Instant deadline = Instant.now().minusSeconds(stuckTimeoutSeconds);
        List<PublishRecord> stuck = repository.findByStatusAndClaimedAtBefore(PublishStatus.PUBLISHING, deadline);
        int recovered = 0;
        for (PublishRecord r : stuck) {
            int updated = repository.casStatusWithReason(r.getId(), PublishStatus.PUBLISHING,
                    PublishStatus.FAILED, "stuck in PUBLISHING over " + stuckTimeoutSeconds + "s");
            if (updated == 1) {
                recovered++;
                log.warn("recovered stuck publish {}", r.getId());
            }
        }
        return recovered;
    }

    /**
     * 并发验证：N 个线程同时抢同一条记录，正确结果是有且只有 1 个成功。
     * 用它来验证乐观锁真的挡住了并发。
     */
    public int race(Long id, int threads) {
        // 先复位成 SCHEDULED，保证同一条记录可以反复验证
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
        log.info("race start on publish {}, current status = {}", id, get(id).getStatus());
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 255 ? s.substring(0, 255) : s;
    }
}
