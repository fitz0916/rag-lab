package com.raglab.retrieval.dao;

import com.raglab.retrieval.model.RerankTrace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class RerankTraceDao {

    private static final RowMapper<RerankTrace> MAPPER = (rs, i) -> new RerankTrace(
            rs.getString("id"),
            rs.getString("query_text"),
            rs.getInt("recall_topn"),
            rs.getInt("final_topk"),
            rs.getInt("hit_count"),
            rs.getLong("cost_ms"),
            rs.getString("status"),
            rs.getString("error_msg"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public RerankTraceDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String id, String query, int recallTopn, int finalTopk) {
        jdbc.update("""
                INSERT INTO kb_rerank_trace(id, query_text, recall_topn, final_topk, status)
                VALUES (?, ?, ?, ?, 'RUNNING')
                """, id, query, recallTopn, finalTopk);
    }

    public void updateResult(String id, int hitCount, String resultJson) {
        jdbc.update("UPDATE kb_rerank_trace SET hit_count = ?, result_json = ? WHERE id = ?",
                hitCount, resultJson, id);
    }

    public void finish(String id, String status, String errorMsg, long costMs) {
        jdbc.update("UPDATE kb_rerank_trace SET status = ?, error_msg = ?, cost_ms = ? WHERE id = ?",
                status, errorMsg, costMs, id);
    }

    public Optional<RerankTrace> findById(String id) {
        List<RerankTrace> list = jdbc.query("SELECT * FROM kb_rerank_trace WHERE id = ?", MAPPER, id);
        return list.stream().findFirst();
    }

    public String findResultJson(String id) {
        List<String> list = jdbc.queryForList(
                "SELECT result_json FROM kb_rerank_trace WHERE id = ?", String.class, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<RerankTrace> listRecent(int limit) {
        return jdbc.query("SELECT * FROM kb_rerank_trace ORDER BY created_at DESC LIMIT ?", MAPPER, limit);
    }
}
