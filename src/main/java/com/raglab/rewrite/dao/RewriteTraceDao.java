package com.raglab.rewrite.dao;

import com.raglab.rewrite.model.RewriteTrace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class RewriteTraceDao {

    private static final RowMapper<RewriteTrace> MAPPER = (rs, i) -> new RewriteTrace(
            rs.getString("id"),
            rs.getString("query_text"),
            rs.getInt("variant_count"),
            rs.getInt("top_k"),
            rs.getInt("recall_topn"),
            rs.getInt("rerank") == 1,
            rs.getLong("cost_ms"),
            rs.getInt("prompt_tokens"),
            rs.getString("status"),
            rs.getString("error_msg"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public RewriteTraceDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String id, String query, int topK, int recallTopN, boolean rerank) {
        jdbc.update("""
                INSERT INTO kb_rewrite_trace(id, query_text, top_k, recall_topn, rerank, status)
                VALUES (?, ?, ?, ?, ?, 'RUNNING')
                """, id, query, topK, recallTopN, rerank ? 1 : 0);
    }

    public void updateResult(String id, int variantCount, int promptTokens, String resultJson) {
        jdbc.update("""
                UPDATE kb_rewrite_trace
                   SET variant_count = ?, prompt_tokens = ?, result_json = ?
                 WHERE id = ?
                """, variantCount, promptTokens, resultJson, id);
    }

    public void finish(String id, String status, String errorMsg, long costMs) {
        jdbc.update("UPDATE kb_rewrite_trace SET status = ?, error_msg = ?, cost_ms = ? WHERE id = ?",
                status, errorMsg, costMs, id);
    }

    public Optional<RewriteTrace> findById(String id) {
        return jdbc.query("SELECT * FROM kb_rewrite_trace WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public String findResultJson(String id) {
        List<String> list = jdbc.queryForList(
                "SELECT result_json FROM kb_rewrite_trace WHERE id = ?", String.class, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<RewriteTrace> listRecent(int limit) {
        return jdbc.query("SELECT * FROM kb_rewrite_trace ORDER BY created_at DESC LIMIT ?", MAPPER, limit);
    }
}
