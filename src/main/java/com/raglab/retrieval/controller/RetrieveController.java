package com.raglab.retrieval.controller;

import com.raglab.common.ApiResponse;
import com.raglab.ingest.log.IngestLogBus;
import com.raglab.retrieval.dao.RetrieveTraceDao;
import com.raglab.retrieval.model.RetrieveTrace;
import com.raglab.retrieval.service.RetrieveService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/retrieve")
public class RetrieveController {

    public record QueryRequest(String query, Integer topK) {
    }

    private final RetrieveService retrieveService;
    private final RetrieveTraceDao traceDao;
    private final IngestLogBus logBus;

    public RetrieveController(RetrieveService retrieveService, RetrieveTraceDao traceDao, IngestLogBus logBus) {
        this.retrieveService = retrieveService;
        this.traceDao = traceDao;
        this.logBus = logBus;
    }

    /** 提交一次检索，立刻返回 traceId；实际执行在异步线程里 */
    @PostMapping
    public ApiResponse<Map<String, String>> submit(@RequestBody QueryRequest req) {
        String traceId = retrieveService.submit(req.query(), req.topK());
        return ApiResponse.ok(Map.of("traceId", traceId));
    }

    /**
     * 检索过程的 SSE 日志流。
     * 和阶段一用同一套日志总线，所以右侧黑窗口的渲染逻辑完全复用。
     */
    @GetMapping(value = "/{traceId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String traceId) {
        RetrieveTrace trace = traceDao.findById(traceId)
                .orElseThrow(() -> new IllegalArgumentException("检索记录不存在：" + traceId));
        return logBus.subscribe(traceId, trace.finished());
    }

    /** 轨迹 + 命中明细 */
    @GetMapping("/{traceId}")
    public ApiResponse<RetrieveService.RetrieveResult> detail(@PathVariable String traceId) {
        return ApiResponse.ok(retrieveService.detail(traceId));
    }

    @GetMapping("/recent")
    public ApiResponse<List<RetrieveTrace>> recent(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(traceDao.listRecent(limit));
    }
}
