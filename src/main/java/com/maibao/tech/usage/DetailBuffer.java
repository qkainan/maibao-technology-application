package com.maibao.tech.usage;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.maibao.tech.usage.domain.UserQueryLog;

/**
 * 明细的批量写缓冲。
 *
 * <p>为什么不用 JPA {@code saveAll}：主键是 {@code IDENTITY}，Hibernate 会退化成逐条 INSERT，
 * 批量等于没做。这里直接走 {@link JdbcTemplate#batchUpdate} 才是真的多值 INSERT。</p>
 *
 * <p>队列满时<b>丢弃并计数</b>，绝不阻塞对话线程 —— 成本已经在 Redis 里累加了，
 * 丢的只是"这一条明细的可追溯性"。</p>
 */
@Component
public class DetailBuffer {

    private static final Logger log = LoggerFactory.getLogger(DetailBuffer.class);

    private static final String INSERT_SQL = """
            INSERT INTO user_query_log
                (request_id, user_id, model, source, prompt, reply,
                 prompt_tokens, completion_tokens, total_tokens, cost_amount,
                 latency_ms, status, error_msg, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private final JdbcTemplate jdbc;
    private final BlockingQueue<Object[]> queue;
    private final int batchSize;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();

    public DetailBuffer(JdbcTemplate jdbc, CostProperties props) {
        this.jdbc = jdbc;
        this.batchSize = props.getDetail().getBatchSize();
        this.queue = new ArrayBlockingQueue<>(props.getDetail().getQueueCapacity());
    }

    /** 只入队，不做任何 IO。 */
    public void offer(UserQueryLog row) {
        if (!queue.offer(toArgs(row))) {
            long total = dropped.incrementAndGet();
            if (total == 1 || total % 1000 == 0) {
                log.warn("detail buffer full, dropped={} (cost already counted in redis)", total);
            }
        }
    }

    /** 定时刷盘：一次最多刷 batchSize 条，队列没清空就继续下一批。 */
    @Scheduled(fixedDelayString = "${app.cost.detail.flush-ms:200}")
    public void flush() {
        List<Object[]> batch = new ArrayList<>(batchSize);
        while (true) {
            batch.clear();
            queue.drainTo(batch, batchSize);
            if (batch.isEmpty()) {
                return;
            }
            try {
                jdbc.batchUpdate(INSERT_SQL, batch);
                written.addAndGet(batch.size());
            } catch (RuntimeException e) {
                log.error("detail batch insert failed, size={}: {}", batch.size(), e.toString());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        flush();
        log.info("detail buffer closed: written={} dropped={} pending={}",
                written.get(), dropped.get(), queue.size());
    }

    public int pending() {
        return queue.size();
    }

    public long written() {
        return written.get();
    }

    public long dropped() {
        return dropped.get();
    }

    private Object[] toArgs(UserQueryLog row) {
        return new Object[]{
                row.getRequestId(),
                row.getUserId(),
                row.getModel(),
                row.getSource(),
                row.getPrompt(),
                row.getReply(),
                row.getPromptTokens(),
                row.getCompletionTokens(),
                row.getTotalTokens(),
                row.getCostAmount(),
                row.getLatencyMs(),
                row.getStatus(),
                row.getErrorMsg(),
                Timestamp.from(row.getCreatedAt())
        };
    }
}
