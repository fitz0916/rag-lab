package com.raglab.ingest.dao;

import com.raglab.ingest.log.LogEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class LogDao {

    /** 保留毫秒：DATETIME(3) → "14:30:12.345" */
    private static final RowMapper<LogEvent> MAPPER = (rs, i) -> {
        String ts = rs.getString("ts_ms");
        if (ts != null && ts.length() > 12) {
            ts = ts.substring(0, 12);
        }
        return new LogEvent(
                rs.getInt("seq"),
                ts,
                rs.getString("level"),
                rs.getString("stage"),
                rs.getString("message"),
                rs.getLong("cost_ms"),
                rs.getString("detail")
        );
    };

    private final JdbcTemplate jdbc;

    public LogDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String taskId, LogEvent e) {
        jdbc.update("""
                INSERT INTO kb_ingest_log(task_id, seq, level, stage, message, detail, cost_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, taskId, e.seq(), e.level(), e.stage(), e.message(), e.detail(), e.costMs());
    }

    public List<LogEvent> findByTask(String taskId) {
        return jdbc.query("""
                SELECT seq, level, stage, message, detail, cost_ms,
                       DATE_FORMAT(created_at, '%H:%i:%s.%f') AS ts_ms
                  FROM kb_ingest_log
                 WHERE task_id = ?
                 ORDER BY seq
                """, MAPPER, taskId);
    }
}
