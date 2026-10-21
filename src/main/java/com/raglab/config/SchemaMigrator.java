package com.raglab.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 轻量级结构迁移：只做「加索引」这类幂等操作。
 *
 * 为什么需要它？
 * schema.sql 用的是 CREATE TABLE IF NOT EXISTS —— 表已存在时它整段跳过，
 * 所以后来才想到的索引加不进去。而 MySQL 8 不支持 ADD INDEX IF NOT EXISTS。
 *
 * 这些索引是日志查询效率的关键：
 *   idx_log_created     按时间倒序列出最近运行记录、按时间清理旧日志
 *   idx_log_level_time  只看 ERROR 时不用扫全表
 *
 * 生产环境应该用 Flyway/Liquibase，这里是学习项目，几十行够用。
 */
@Component
public class SchemaMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    /** 表名 → (索引名, 列定义) */
    private static final List<String[]> WANTED_INDEXES = List.of(
            // 按时间清理旧日志：实测 5 万行时走 covering index range scan，取 1000 行 0.36ms
            new String[]{"kb_ingest_log", "idx_log_created", "created_at"},
            // 只看 ERROR 时按级别筛
            new String[]{"kb_ingest_log", "idx_log_level_time", "level, created_at"},
            // 运行记录列表里的日志统计：COUNT + 判 ERROR，覆盖索引避免回表
            new String[]{"kb_ingest_log", "idx_log_task_level", "task_id, level"},
            new String[]{"kb_retrieve_trace", "idx_trace_time2", "created_at"},
            new String[]{"kb_hybrid_trace", "idx_hybrid_time2", "created_at"}
    );

    private final JdbcTemplate jdbc;

    public SchemaMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String[] idx : WANTED_INDEXES) {
            try {
                ensureIndex(idx[0], idx[1], idx[2]);
            } catch (Exception e) {
                // 迁移失败不能阻止应用启动，但要吵出来
                log.warn("索引迁移失败 table={} index={} : {}", idx[0], idx[1], e.getMessage());
            }
        }
    }

    private void ensureIndex(String table, String indexName, String columns) {
        Integer exists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                """, Integer.class, table, indexName);
        if (exists != null && exists > 0) {
            return;
        }
        // 表可能还不存在（首次启动 schema.sql 尚未执行完），跳过即可
        Integer tableExists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_name = ?
                """, Integer.class, table);
        if (tableExists == null || tableExists == 0) {
            log.info("表 {} 不存在，跳过索引 {} 的创建", table, indexName);
            return;
        }
        jdbc.execute("CREATE INDEX %s ON %s(%s)".formatted(indexName, table, columns));
        log.info("已创建索引 {}.{} ({})", table, indexName, columns);
    }
}
