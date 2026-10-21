package com.raglab.eval.dao;

import com.raglab.eval.model.EvalRun;
import com.raglab.eval.model.EvalScore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class EvalRunDao {

    private static final RowMapper<EvalRun> MAPPER = (rs, i) -> new EvalRun(
            rs.getString("id"),
            rs.getString("strategies"),
            rs.getInt("top_k"),
            rs.getInt("recall_topn"),
            rs.getInt("case_count"),
            rs.getString("status"),
            rs.getLong("cost_ms"),
            rs.getString("error_msg"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public EvalRunDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String id, String strategies, int topK, int recallTopN, int caseCount) {
        jdbc.update("""
                INSERT INTO kb_eval_run(id, strategies, top_k, recall_topn, case_count, status)
                VALUES (?, ?, ?, ?, ?, 'RUNNING')
                """, id, strategies, topK, recallTopN, caseCount);
    }

    public void finish(String id, String status, String errorMsg, long costMs, String summaryJson) {
        jdbc.update("""
                UPDATE kb_eval_run
                   SET status = ?, error_msg = ?, cost_ms = ?, summary_json = ?
                 WHERE id = ?
                """, status, errorMsg, costMs, summaryJson, id);
    }

    public Optional<EvalRun> findById(String id) {
        return jdbc.query("SELECT * FROM kb_eval_run WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public String findSummaryJson(String id) {
        List<String> list = jdbc.queryForList(
                "SELECT summary_json FROM kb_eval_run WHERE id = ?", String.class, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<EvalRun> listRecent(int limit) {
        return jdbc.query("SELECT * FROM kb_eval_run ORDER BY created_at DESC LIMIT ?", MAPPER, limit);
    }

    /**
     * 明细表：一行 = 一个「用例 × 策略」。
     *
     * 为什么明细还要单独落表，而不是只塞 JSON？
     *   因为这样能用 SQL 直接问「上个月所有 HARD 用例里，哪个策略最差」，
     *   不用把 JSON 全捞到内存里再算。
     */
    public void insertDetail(String runId, long caseId, String strategy, EvalScore score,
                             long latencyMs, String errorMsg) {
        jdbc.update("""
                INSERT INTO kb_eval_detail(run_id, case_id, strategy, hit1, hit3, hit_k,
                                           first_rank, mrr, coverage, matched, latency_ms, error_msg)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, runId, caseId, strategy,
                score.hit1() ? 1 : 0, score.hit3() ? 1 : 0, score.hitK() ? 1 : 0,
                score.firstRank(), score.mrr(), score.coverage(),
                truncate(String.join(" | ", score.matched()), 480),
                latencyMs, truncate(errorMsg, 950));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
