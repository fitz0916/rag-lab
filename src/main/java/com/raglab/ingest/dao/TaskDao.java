package com.raglab.ingest.dao;

import com.raglab.ingest.model.TaskSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class TaskDao {

    private static final RowMapper<TaskSnapshot> MAPPER = (rs, i) -> new TaskSnapshot(
            rs.getString("id"),
            (Long) rs.getObject("doc_id"),
            rs.getString("doc_name"),
            rs.getString("status"),
            rs.getString("current_stage"),
            rs.getInt("total_chunks"),
            rs.getInt("done_chunks"),
            rs.getInt("stored_vectors"),
            rs.getString("error_msg"),
            rs.getString("error_stage"),
            rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toLocalDateTime(),
            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toLocalDateTime(),
            rs.getLong("cost_ms")
    );

    private final JdbcTemplate jdbc;

    public TaskDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String taskId, String docName) {
        jdbc.update("INSERT INTO kb_ingest_task(id, doc_name, status) VALUES (?, ?, 'RUNNING')",
                taskId, docName);
    }

    public void bindDoc(String taskId, long docId) {
        jdbc.update("UPDATE kb_ingest_task SET doc_id = ? WHERE id = ?", docId, taskId);
    }

    public void updateStage(String taskId, String stage) {
        jdbc.update("UPDATE kb_ingest_task SET current_stage = ? WHERE id = ?", stage, taskId);
    }

    public void updateTotal(String taskId, int total) {
        jdbc.update("UPDATE kb_ingest_task SET total_chunks = ? WHERE id = ?", total, taskId);
    }

    public void updateProgress(String taskId, int doneChunks, int storedVectors) {
        jdbc.update("UPDATE kb_ingest_task SET done_chunks = ?, stored_vectors = ? WHERE id = ?",
                doneChunks, storedVectors, taskId);
    }

    public void finish(String taskId, String status, String errorMsg, String errorStage, long costMs) {
        jdbc.update("""
                UPDATE kb_ingest_task
                   SET status = ?, error_msg = ?, error_stage = ?, cost_ms = ?, finished_at = NOW()
                 WHERE id = ?
                """, status, errorMsg, errorStage, costMs, taskId);
    }

    public Optional<TaskSnapshot> findById(String taskId) {
        List<TaskSnapshot> list = jdbc.query("SELECT * FROM kb_ingest_task WHERE id = ?", MAPPER, taskId);
        return list.stream().findFirst();
    }

    public List<TaskSnapshot> listRecent(int limit) {
        return jdbc.query("SELECT * FROM kb_ingest_task ORDER BY started_at DESC LIMIT ?", MAPPER, limit);
    }
}
