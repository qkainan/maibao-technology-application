package com.maibao.tech.usage.detail;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 明细的批量写库。本地直写（{@code mode=local}）和 MQ 消费端共用这一份 SQL 与参数映射，
 * 避免两处各写一遍 INSERT 然后慢慢跑偏。
 *
 * <p>为什么不用 JPA {@code saveAll}：主键是 {@code IDENTITY}，Hibernate 会关掉 JDBC 批量，
 * 结果还是逐条 INSERT。</p>
 */
@Component
public class DetailWriter {

    /**
     * {@code INSERT IGNORE}：{@code request_id} 上有唯一键，重复投递的批次整批跳过即可，
     * 不需要消费端自己维护去重表。
     */
    private static final String INSERT_SQL = """
            INSERT IGNORE INTO user_query_log
                (request_id, user_id, model, source, prompt, reply,
                 prompt_tokens, completion_tokens, total_tokens, cost_amount,
                 latency_ms, status, error_msg, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private final JdbcTemplate jdbc;

    public DetailWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return 提交的行数（{@code IGNORE} 掉的不计入）
     */
    public int write(List<DetailRecord> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<Object[]> args = new ArrayList<>(rows.size());
        for (DetailRecord row : rows) {
            args.add(toArgs(row));
        }
        return jdbc.batchUpdate(INSERT_SQL, args).length;
    }

    private Object[] toArgs(DetailRecord row) {
        return new Object[]{
                row.requestId(),
                row.userId(),
                row.model(),
                row.source(),
                row.prompt(),
                row.reply(),
                row.promptTokens(),
                row.completionTokens(),
                row.totalTokens(),
                row.costAmount(),
                row.latencyMs(),
                row.status(),
                row.errorMsg(),
                Timestamp.from(row.createdAt())
        };
    }
}
