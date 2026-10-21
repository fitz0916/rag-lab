package com.raglab.retrieval.dao;

import com.raglab.retrieval.model.RetrieveTrace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class RetrieveTraceDao {

    private static final RowMapper<RetrieveTrace> MAPPER = (rs, i) -> new RetrieveTrace(
            rs.getString("id"),
            rs.getString("query_text"),
            rs.getInt("top_k"),
            rs.getInt("hit_count"),
            (Double) rs.getObject("top1_score"),
            (Integer) rs.getObject("query_dim"),
            (Double) rs.getObject("query_norm"),
            rs.getString("query_head"),
            (Integer) rs.getObject("chroma_count"),
            rs.getLong("cost_ms"),
            rs.getString("status"),
            rs.getString("error_msg"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public RetrieveTraceDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String id, String queryText, int topK) {
        jdbc.update("INSERT INTO kb_retrieve_trace(id, query_text, top_k, status) VALUES (?, ?, ?, 'RUNNING')",
                id, queryText, topK);
    }

    public void updateQueryVector(String id, int dim, double norm, String head) {
        jdbc.update("UPDATE kb_retrieve_trace SET query_dim = ?, query_norm = ?, query_head = ? WHERE id = ?",
                dim, norm, head, id);
    }

    public void updateResult(String id, int hitCount, Double top1Score, Integer chromaCount, String hitsJson) {
        jdbc.update("""
                UPDATE kb_retrieve_trace
                   SET hit_count = ?, top1_score = ?, chroma_count = ?, hits_json = ?
                 WHERE id = ?
                """, hitCount, top1Score, chromaCount, hitsJson, id);
    }

    public void finish(String id, String status, String errorMsg, long costMs) {
        jdbc.update("UPDATE kb_retrieve_trace SET status = ?, error_msg = ?, cost_ms = ? WHERE id = ?",
                status, errorMsg, costMs, id);
    }

    public Optional<RetrieveTrace> findById(String id) {
        List<RetrieveTrace> list = jdbc.query("SELECT * FROM kb_retrieve_trace WHERE id = ?", MAPPER, id);
        return list.stream().findFirst();
    }

    public String findHitsJson(String id) {
        List<String> list = jdbc.queryForList(
                "SELECT hits_json FROM kb_retrieve_trace WHERE id = ?", String.class, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<RetrieveTrace> listRecent(int limit) {
        return jdbc.query("SELECT * FROM kb_retrieve_trace ORDER BY created_at DESC LIMIT ?", MAPPER, limit);
    }
}
