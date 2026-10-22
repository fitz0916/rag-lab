package com.raglab.gen.dao;

import com.raglab.gen.model.GenerateTrace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class GenerateTraceDao {

    private static final RowMapper<GenerateTrace> MAPPER = (rs, i) -> new GenerateTrace(
            rs.getString("id"),
            rs.getString("query_text"),
            rs.getString("model"),
            rs.getInt("source_chunks"),
            rs.getInt("sentence_count"),
            rs.getInt("context_chars"),
            rs.getInt("prompt_chars"),
            rs.getInt("answer_chars"),
            rs.getInt("prompt_tokens"),
            rs.getInt("completion_tokens"),
            rs.getInt("cited_count"),
            rs.getInt("cache_hit") == 1,
            rs.getString("cache_layer"),
            (Double) rs.getObject("cache_similarity"),
            rs.getLong("cost_ms"),
            rs.getString("status"),
            rs.getString("error_msg"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public GenerateTraceDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String id, String query, String model) {
        jdbc.update("""
                INSERT INTO kb_generate_trace(id, query_text, model, status)
                VALUES (?, ?, ?, 'RUNNING')
                """, id, query, model);
    }

    public void updateResult(String id, int sourceChunks, int sentenceCount, int contextChars,
                             int promptChars, int answerChars, int promptTokens, int completionTokens,
                             int citedCount, String resultJson) {
        jdbc.update("""
                UPDATE kb_generate_trace
                   SET source_chunks = ?, sentence_count = ?, context_chars = ?, prompt_chars = ?,
                       answer_chars = ?, prompt_tokens = ?, completion_tokens = ?, cited_count = ?,
                       result_json = ?
                 WHERE id = ?
                """, sourceChunks, sentenceCount, contextChars, promptChars, answerChars,
                promptTokens, completionTokens, citedCount, resultJson, id);
    }

    /** 命中缓存时标记，便于在运行记录里一眼看出「这次没花钱」 */
    public void markCacheHit(String id, String layer, double similarity) {
        jdbc.update("UPDATE kb_generate_trace SET cache_hit = 1, cache_layer = ?, cache_similarity = ? WHERE id = ?",
                layer, similarity, id);
    }

    public void finish(String id, String status, String errorMsg, long costMs) {
        jdbc.update("UPDATE kb_generate_trace SET status = ?, error_msg = ?, cost_ms = ? WHERE id = ?",
                status, errorMsg, costMs, id);
    }

    public Optional<GenerateTrace> findById(String id) {
        return jdbc.query("SELECT * FROM kb_generate_trace WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public String findResultJson(String id) {
        List<String> list = jdbc.queryForList(
                "SELECT result_json FROM kb_generate_trace WHERE id = ?", String.class, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<GenerateTrace> listRecent(int limit) {
        return jdbc.query("SELECT * FROM kb_generate_trace ORDER BY created_at DESC LIMIT ?", MAPPER, limit);
    }
}
