package com.maibao.tech.publish;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * 发布执行线程池：与 Web 容器线程隔离，避免长任务占满请求线程。
 *
 * <p>队列满时用 CallerRunsPolicy 回退到提交线程（调度线程）执行 —— 这是背压：
 * 宁可让扫描慢下来，也不要把任务丢掉，丢掉会让记录永远停在 PUBLISHING。</p>
 */
@Component
public class PublishExecutor {

    private static final Logger log = LoggerFactory.getLogger(PublishExecutor.class);

    private final ThreadPoolExecutor pool;

    public PublishExecutor(@Value("${app.publish.executor.core-size:4}") int coreSize,
                           @Value("${app.publish.executor.max-size:8}") int maxSize,
                           @Value("${app.publish.executor.queue-capacity:100}") int queueCapacity) {
        AtomicInteger seq = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(coreSize, maxSize, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread t = new Thread(runnable, "publish-exec-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public void submit(Runnable task) {
        pool.execute(task);
    }

    public int activeCount() {
        return pool.getActiveCount();
    }

    public int queueSize() {
        return pool.getQueue().size();
    }

    /** 停机时把队列里的任务跑完，避免留下停在 PUBLISHING 的记录 */
    @PreDestroy
    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("publish executor did not terminate in 5s, forcing shutdown");
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
