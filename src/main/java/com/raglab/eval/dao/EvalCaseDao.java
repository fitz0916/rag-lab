package com.raglab.eval.dao;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raglab.eval.model.EvalCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class EvalCaseDao {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public EvalCaseDao(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<EvalCase> MAPPER = (rs, i) -> new EvalCase(
            rs.getLong("id"),
            rs.getString("case_name"),
            rs.getString("query_text"),
            parseExpect(rs.getString("expect_json")),
            rs.getString("difficulty"),
            rs.getInt("enabled") == 1,
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private List<List<String>> parseExpect(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<List<String>>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<EvalCase> listAll(boolean onlyEnabled) {
        String sql = "SELECT * FROM kb_eval_case "
                + (onlyEnabled ? "WHERE enabled = 1 " : "")
                + "ORDER BY id";
        return jdbc.query(sql, MAPPER);
    }

    public List<EvalCase> listByIds(List<Long> ids, boolean onlyEnabled) {
        if (ids == null || ids.isEmpty()) {
            return listAll(onlyEnabled);
        }
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            in.append(i == 0 ? "?" : ",?");
        }
        Object[] args = ids.toArray();
        String sql = "SELECT * FROM kb_eval_case WHERE id IN (" + in + ") "
                + (onlyEnabled ? "AND enabled = 1 " : "")
                + "ORDER BY id";
        return jdbc.query(sql, MAPPER, args);
    }

    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM kb_eval_case", Integer.class);
        return n == null ? 0 : n;
    }

    public Optional<EvalCase> findById(long id) {
        return jdbc.query("SELECT * FROM kb_eval_case WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public long insert(String name, String query, String expectJson, String difficulty) {
        jdbc.update("""
                INSERT INTO kb_eval_case(case_name, query_text, expect_json, difficulty)
                VALUES (?, ?, ?, ?)
                """, name, query, expectJson, difficulty);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0 : id;
    }

    public void setEnabled(long id, boolean enabled) {
        jdbc.update("UPDATE kb_eval_case SET enabled = ? WHERE id = ?", enabled ? 1 : 0, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM kb_eval_case WHERE id = ?", id);
    }

    /** 批量插入种子用例（只用于首次启动） */
    public int insertBatch(List<List<Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<Object[]> args = new ArrayList<>(rows.size());
        for (List<Object> r : rows) {
            args.add(r.toArray());
        }
        jdbc.batchUpdate("""
                INSERT INTO kb_eval_case(case_name, query_text, expect_json, difficulty)
                VALUES (?, ?, ?, ?)
                """, args);
        return args.size();
    }
}
