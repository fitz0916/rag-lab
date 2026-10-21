package com.raglab.ingest.controller;

import com.raglab.common.ApiResponse;
import com.raglab.config.RagLabProperties;
import com.raglab.ingest.dao.RunRecordDao;
import com.raglab.ingest.model.RunRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运行记录：把入库 / 向量检索 / 混合检索三类任务统一成一个历史列表。
 *
 * 这个接口的设计目标就一个：**快**。
 *   - 列表不查 detail（MEDIUMTEXT，占表体积 1/3）
 *   - 三个子查询各自 LIMIT 走索引，再归并
 *   - 日志条数一次 GROUP BY 批量取，不做 N+1
 */
@RestController
@RequestMapping("/api/runs")
public class RunController {

    public record RunListResponse(
            List<RunRecord> records,
            long totalLogRows,
            int retentionDays,
            String note
    ) {
    }

    private final RunRecordDao runRecordDao;
    private final JdbcTemplate jdbc;
    private final RagLabProperties props;

    public RunController(RunRecordDao runRecordDao, JdbcTemplate jdbc, RagLabProperties props) {
        this.runRecordDao = runRecordDao;
        this.jdbc = jdbc;
        this.props = props;
    }

    @GetMapping
    public ApiResponse<RunListResponse> list(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "ALL") String type,
            @RequestParam(defaultValue = "false") boolean onlyFailed) {

        int n = Math.max(1, Math.min(limit, 200));
        List<RunRecord> records = runRecordDao.recent(n, type, onlyFailed);

        // 精确 COUNT，用于展示日志表规模。几十万行以内都是毫秒级；
        // 如果日志表涨到百万行以上，这里应改成 information_schema 估算值或加缓存，
        // 但更根本的办法是让 retention-days 把表控制住。
        Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM kb_ingest_log", Long.class);
        int retention = props.log() == null ? 0 : props.log().effectiveRetentionDays();

        String note = retention > 0
                ? "日志保留 %d 天，列表不加载 detail，点开某条记录才回放完整日志".formatted(retention)
                : "日志永久保留（raglab.log.retention-days <= 0），注意表会持续增长";

        return ApiResponse.ok(new RunListResponse(records, rows == null ? 0 : rows, retention, note));
    }
}
