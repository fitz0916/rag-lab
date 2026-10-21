package com.raglab.ingest.log;

import com.raglab.config.RagLabProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 日志保留策略。
 *
 * 不清理的话 kb_ingest_log 会无限增长，最终把「按时间查最近记录」拖成全表扫 ——
 * 这正是「保存日志」和「查询效率」之间的平衡点。
 *
 * 默认保留 30 天，可用 raglab.log.retention-days 调。
 * 分批删除，避免一条 DELETE 锁表太久。
 */
@Component
public class LogRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(LogRetentionJob.class);
    private static final int BATCH = 1000;

    private final JdbcTemplate jdbc;
    private final RagLabProperties props;

    public LogRetentionJob(JdbcTemplate jdbc, RagLabProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** 每天 03:30 执行一次 */
    @Scheduled(cron = "0 30 3 * * ?")
    public void purge() {
        int days = props.log() == null ? 0 : props.log().effectiveRetentionDays();
        if (days <= 0) {
            log.debug("日志保留天数 <= 0，跳过清理");
            return;
        }
        LocalDateTime before = LocalDateTime.now().minusDays(days);
        int total = 0;
        while (true) {
            int n = jdbc.update("DELETE FROM kb_ingest_log WHERE created_at < ? LIMIT ?", before, BATCH);
            total += n;
            if (n < BATCH) {
                break;
            }
        }
        if (total > 0) {
            log.info("已清理 {} 条早于 {} 的日志", total, before);
        }
    }
}
