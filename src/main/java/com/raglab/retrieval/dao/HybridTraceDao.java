package com.raglab.retrieval.dao;

import com.raglab.retrieval.model.HybridTrace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class HybridTraceDao {

    private static final RowMapper<HybridTrace> MAPPER = (rs, i) -> new HybridTrace(
            rs.getString("id"),
            rs.getString("query_text"),
            rs.getInt("vec_topn"),
            rs.getInt("bm25_topn"),
            rs.getInt("final_topk"),
            rs.getInt("rrf_k"),
            rs.getInt("hit_count"),
            rs.getLong("cost_ms"),
            rs.getString("status"),
            rs.getString("error_msg"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public HybridTraceDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String id, String query, int vecTopn, int bm25Topn, int finalTopk, int rrfK) {
        jdbc.update("""
                INSERT INTO kb_hybrid_trace(id, query_text, vec_topn, bm25_topn, final_topk, rrf_k, status)
                VALUES (?, ?, ?, ?, ?, ?, 'RUNNING')
                """, id, query, vecTopn, bm25Topn, finalTopk, rrfK);
    }

    public void updateResult(String id, int hitCount, String resultJson) {
        jdbc.update("UPDATE kb_hybrid_trace SET hit_count = ?, result_json = ? WHERE id = ?",
                hitCount, resultJson, id);
    }

    public void finish(String id, String status, String errorMsg, long costMs) {
        jdbc.update("UPDATE kb_hybrid_trace SET status = ?, error_msg = ?, cost_ms = ? WHERE id = ?",
                status, errorMsg, costMs, id);
    }

    public Optional<HybridTrace> findById(String id) {
        List<HybridTrace> list = jdbc.query("SELECT * FROM kb_hybrid_trace WHERE id = ?", MAPPER, id);
        return list.stream().findFirst();
    }

    public String findResultJson(String id) {
        List<String> list = jdbc.queryForList(
                "SELECT result_json FROM kb_hybrid_trace WHERE id = ?", String.class, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<HybridTrace> listRecent(int limit) {
        return jdbc.query("SELECT * FROM kb_hybrid_trace ORDER BY created_at DESC LIMIT ?", MAPPER, limit);
    }
}
