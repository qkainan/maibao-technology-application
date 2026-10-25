package com.maibao.tech.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 线程池：流式转发是 IO 密集 + 长连接，必须和业务线程池隔离。
 *
 * <p>选型要点：核心线程、队列容量、拒绝策略为什么这么定。</p>
 */
@Configuration
public class ExecutorConfig {

    public record StreamExecutorHolder(ExecutorService executor) {
    }

    /** SSE 心跳调度：单线程足够，只负责每 15s 发一个注释帧 */
    @Bean(name = "streamHeartbeatScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService streamHeartbeatScheduler() {
        AtomicInteger seq = new AtomicInteger();
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sse-heartbeat-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    public StreamExecutorHolder streamExecutorHolder() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "maibao-stream-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        ExecutorService pool = new ThreadPoolExecutor(
                8,          // core
                32,         // max
                60,         // keepAlive（秒）
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(200),
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy());  // 队列满时回退到调用线程，避免静默丢任务
        return new StreamExecutorHolder(pool);
    }
}
