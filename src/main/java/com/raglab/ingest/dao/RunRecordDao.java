package com.raglab.ingest.dao;

import com.raglab.ingest.model.RunRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行记录查询。
 *
 * 查询效率的三个设计点（这就是「保存日志」和「查得快」之间的平衡）：
 *
 * 1. **每个子查询各自 LIMIT，再归并排序**
 *    不是 `UNION ALL 三张全表 → ORDER BY → LIMIT`，那样会把三张表全读一遍。
 *    而是 `(A ORDER BY time LIMIT n) UNION (B ...) UNION (C ...) ORDER BY LIMIT n`，
 *    每段都能走 created_at 索引取前 n 条，归并量很小。
 *
 * 2. **列表只聚合日志条数，不读 detail**
 *    kb_ingest_log.detail 是 MEDIUMTEXT，实测占了表体积的 1/3。
 *    列表只需要「几条日志、几条错误」，一条 GROUP BY 聚合就够，
 *    配合 idx_log_task_level(task_id, level) 复合索引，连回表都不用。
 *
 * 3. **日志统计一次批量查**
 *    不是对每条记录单独 count（N+1 查询），而是一条 `WHERE task_id IN (...)` 搞定。
 */
@Repository
public class RunRecordDao {

    private static final RowMapper<RunRecord> MAPPER = (rs, i) -> new RunRecord(
            rs.getString("run_type"),
            rs.getString("id"),
            rs.getString("title"),
            rs.getString("status"),
            rs.getLong("cost_ms"),
            rs.getTimestamp("ts").toLocalDateTime(),
            rs.getString("summary"),
            rs.getString("error_msg"),
            0, 0    // 日志条数在第二步批量填充
    );

    private final JdbcTemplate jdbc;

    public RunRecordDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param limit      最多返回多少条
     * @param type       ALL / INGEST / RETRIEVE / HYBRID
     * @param onlyFailed 只看失败的
     */
    public List<RunRecord> recent(int limit, String type, boolean onlyFailed) {
        String t = type == null ? "ALL" : type.toUpperCase();
        boolean all = "ALL".equals(t) || t.isBlank();
        boolean ingest = all || RunRecord.TYPE_INGEST.equals(t);
        boolean retrieve = all || RunRecord.TYPE_RETRIEVE.equals(t);
        boolean hybrid = all || RunRecord.TYPE_HYBRID.equals(t);
        boolean rerank = all || RunRecord.TYPE_RERANK.equals(t);
        boolean context = all || RunRecord.TYPE_CONTEXT.equals(t);
        boolean eval = all || RunRecord.TYPE_EVAL.equals(t);
        boolean generate = all || RunRecord.TYPE_GENERATE.equals(t);
        boolean rewrite = all || RunRecord.TYPE_REWRITE.equals(t);

        if (!ingest && !retrieve && !hybrid && !rerank && !context && !eval && !generate && !rewrite) {
            return List.of();
        }

        StringBuilder sql = new StringBuilder("SELECT * FROM (");
        List<Object> args = new ArrayList<>();
        boolean first = true;
        String failCond = onlyFailed ? " AND status = 'FAILED' " : " ";

        if (ingest) {
            sql.append("""
                    (SELECT 'INGEST' AS run_type, id, doc_name AS title, status, cost_ms, started_at AS ts,
                            CONCAT(IFNULL(total_chunks,0), ' 片 / ', IFNULL(stored_vectors,0), ' 向量') AS summary,
                            error_msg
                       FROM kb_ingest_task
                      WHERE 1=1 """).append(failCond)
                    .append("ORDER BY started_at DESC LIMIT ?)");
            args.add(limit);
            first = false;
        }
        if (retrieve) {
            if (!first) {
                sql.append(" UNION ALL ");
            }
            sql.append("""
                    (SELECT 'RETRIEVE' AS run_type, id, query_text AS title, status, cost_ms, created_at AS ts,
                            CONCAT('Top-', top_k, ' · 命中 ', hit_count) AS summary,
                            error_msg
                       FROM kb_retrieve_trace
                      WHERE 1=1 """).append(failCond)
                    .append("ORDER BY created_at DESC LIMIT ?)");
            args.add(limit);
            first = false;
        }
        if (hybrid) {
            if (!first) {
                sql.append(" UNION ALL ");
            }
            sql.append("""
                    (SELECT 'HYBRID' AS run_type, id, query_text AS title, status, cost_ms, created_at AS ts,
                            CONCAT('两路 Top-', vec_topn, '+', bm25_topn, ' · 融合 ', hit_count) AS summary,
                            error_msg
                       FROM kb_hybrid_trace
                      WHERE 1=1 """).append(failCond)
                    .append("ORDER BY created_at DESC LIMIT ?)");
            args.add(limit);
            first = false;
        }
        if (rerank) {
            if (!first) {
                sql.append(" UNION ALL ");
            }
            sql.append("""
                    (SELECT 'RERANK' AS run_type, id, query_text AS title, status, cost_ms, created_at AS ts,
                            CONCAT('召回 Top-', recall_topn, ' → 重排 Top-', final_topk, ' · 命中 ', hit_count) AS summary,
                            error_msg
                       FROM kb_rerank_trace
                      WHERE 1=1 """).append(failCond)
                    .append("ORDER BY created_at DESC LIMIT ?)");
            args.add(limit);
            first = false;
        }
        if (context) {
            if (!first) {
                sql.append(" UNION ALL ");
            }
            sql.append("""
                    (SELECT 'CONTEXT' AS run_type, id, query_text AS title, status, cost_ms, created_at AS ts,
                            CONCAT(origin_chars, ' 字 → ', final_chars, ' 字 · ', sentence_count, ' 句 · ', strategy) AS summary,
                            error_msg
                       FROM kb_context_trace
                      WHERE 1=1 """).append(failCond)
                    .append("ORDER BY created_at DESC LIMIT ?)");
            args.add(limit);
            first = false;
        }
        if (eval) {
            if (!first) {
                sql.append(" UNION ALL ");
            }
            sql.append("""
                    (SELECT 'EVAL' AS run_type, id, CONCAT(strategies, ' · ', case_count, ' 用例') AS title,
                            status, cost_ms, created_at AS ts,
                            CONCAT('Top-', top_k, ' · 召回 ', recall_topn, ' · ', case_count, ' 用例 × ',
                                   LENGTH(strategies) - LENGTH(REPLACE(strategies, ',', '')) + 1, ' 策略') AS summary,
                            error_msg
                       FROM kb_eval_run
                      WHERE 1=1 """).append(failCond)
                    .append("ORDER BY created_at DESC LIMIT ?)");
            args.add(limit);
            first = false;
        }
        if (generate) {
            if (!first) {
                sql.append(" UNION ALL ");
            }
            sql.append("""
                    (SELECT 'GENERATE' AS run_type, id, query_text AS title, status, cost_ms, created_at AS ts,
                            CONCAT(IF(cache_hit = 1, CONCAT('缓存命中 ', cache_layer, ' · '), ''),
                                   source_chunks, ' 片 → ', sentence_count, ' 句 · ', prompt_tokens,
                                   ' prompt tokens · 引用 ', cited_count) AS summary,
                            error_msg
                       FROM kb_generate_trace
                      WHERE 1=1 """).append(failCond)
                    .append("ORDER BY created_at DESC LIMIT ?)");
            args.add(limit);
            first = false;
        }
        if (rewrite) {
            if (!first) {
                sql.append(" UNION ALL ");
            }
            sql.append("""
                    (SELECT 'REWRITE' AS run_type, id, query_text AS title, status, cost_ms, created_at AS ts,
                            CONCAT(variant_count, ' 变体 · Top-', top_k, ' · ', prompt_tokens,
                                   ' tokens', IF(rerank = 1, ' · 重排', '')) AS summary,
                            error_msg
                       FROM kb_rewrite_trace
                      WHERE 1=1 """).append(failCond)
                    .append("ORDER BY created_at DESC LIMIT ?)");
            args.add(limit);
            first = false;
        }
        sql.append(") t ORDER BY ts DESC LIMIT ?");
        args.add(limit);

        List<RunRecord> raw = jdbc.query(sql.toString(), MAPPER, args.toArray());
        return fillLogStats(raw);
    }

    /** 一次 IN 查询把一堆任务的日志条数/错误数都取回来 */
    private List<RunRecord> fillLogStats(List<RunRecord> raw) {
        if (raw.isEmpty()) {
            return raw;
        }
        List<String> ids = raw.stream().map(RunRecord::id).toList();
        String placeholders = String.join(",", ids.stream().map(x -> "?").toList());
        String sql = """
                SELECT task_id,
                       COUNT(*) AS total,
                       SUM(IF(level = 'ERROR', 1, 0)) AS errs
                  FROM kb_ingest_log
                 WHERE task_id IN (%s)
                 GROUP BY task_id
                """.formatted(placeholders);

        Map<String, int[]> stats = new HashMap<>();
        jdbc.query(sql, rs -> {
            stats.put(rs.getString("task_id"),
                    new int[]{rs.getInt("total"), rs.getInt("errs")});
        }, ids.toArray());

        List<RunRecord> out = new ArrayList<>(raw.size());
        for (RunRecord r : raw) {
            int[] s = stats.getOrDefault(r.id(), new int[]{0, 0});
            out.add(new RunRecord(r.type(), r.id(), r.title(), r.status(), r.costMs(), r.ts(),
                    r.summary(), r.errorMsg(), s[0], s[1]));
        }
        return out;
    }
}
