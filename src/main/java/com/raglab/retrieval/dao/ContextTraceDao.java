package com.raglab.retrieval.dao;

import com.raglab.retrieval.model.ContextTrace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ContextTraceDao {

    private static final RowMapper<ContextTrace> MAPPER = (rs, i) -> new ContextTrace(
            rs.getString("id"),
            rs.getString("query_text"),
            rs.getInt("recall_topk"),
            rs.getString("strategy"),
            rs.getInt("max_chars"),
            rs.getInt("origin_chars"),
            rs.getInt("final_chars"),
            rs.getInt("sentence_count"),
            (Double) rs.getObject("compress_ratio"),
            rs.getLong("cost_ms"),
            rs.getString("status"),
            rs.getString("error_msg"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public ContextTraceDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String id, String query, int recallTopk, String strategy, int maxChars) {
        jdbc.update("""
                INSERT INTO kb_context_trace(id, query_text, recall_topk, strategy, max_chars, status)
                VALUES (?, ?, ?, ?, ?, 'RUNNING')
                """, id, query, recallTopk, strategy, maxChars);
    }

    public void updateResult(String id, int originChars, int finalChars, int sentenceCount,
                             double ratio, String resultJson) {
        jdbc.update("""
                UPDATE kb_context_trace
                   SET origin_chars = ?, final_chars = ?, sentence_count = ?,
                       compress_ratio = ?, result_json = ?
                 WHERE id = ?
                """, originChars, finalChars, sentenceCount, ratio, resultJson, id);
    }

    public void finish(String id, String status, String errorMsg, long costMs) {
        jdbc.update("UPDATE kb_context_trace SET status = ?, error_msg = ?, cost_ms = ? WHERE id = ?",
                status, errorMsg, costMs, id);
    }

    public Optional<ContextTrace> findById(String id) {
        List<ContextTrace> list = jdbc.query("SELECT * FROM kb_context_trace WHERE id = ?", MAPPER, id);
        return list.stream().findFirst();
    }

    public String findResultJson(String id) {
        List<String> list = jdbc.queryForList(
                "SELECT result_json FROM kb_context_trace WHERE id = ?", String.class, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<ContextTrace> listRecent(int limit) {
        return jdbc.query("SELECT * FROM kb_context_trace ORDER BY created_at DESC LIMIT ?", MAPPER, limit);
    }
}
