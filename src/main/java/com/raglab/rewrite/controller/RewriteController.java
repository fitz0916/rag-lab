package com.raglab.rewrite.controller;

import com.raglab.common.ApiResponse;
import com.raglab.ingest.log.IngestLogBus;
import com.raglab.rewrite.dao.RewriteTraceDao;
import com.raglab.rewrite.model.RewriteTrace;
import com.raglab.rewrite.service.RewriteService;
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
@RequestMapping("/api/rewrite")
public class RewriteController {

    public record RewriteRequest(String query, Integer variants, Integer topK,
                                 Integer recallN, Boolean rerank) {
    }

    private final RewriteService rewriteService;
    private final RewriteTraceDao traceDao;
    private final IngestLogBus logBus;

    public RewriteController(RewriteService rewriteService, RewriteTraceDao traceDao,
                             IngestLogBus logBus) {
        this.rewriteService = rewriteService;
        this.traceDao = traceDao;
        this.logBus = logBus;
    }

    @PostMapping
    public ApiResponse<Map<String, String>> submit(@RequestBody RewriteRequest req) {
        String traceId = rewriteService.submit(req.query(), req.variants(), req.topK(),
                req.recallN(), req.rerank());
        return ApiResponse.ok(Map.of("traceId", traceId));
    }

    @GetMapping(value = "/{traceId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String traceId) {
        RewriteTrace trace = traceDao.findById(traceId)
                .orElseThrow(() -> new IllegalArgumentException("记录不存在：" + traceId));
        return logBus.subscribe(traceId, trace.finished());
    }

    @GetMapping("/{traceId}")
    public ApiResponse<RewriteService.RewriteDetail> detail(@PathVariable String traceId) {
        return ApiResponse.ok(rewriteService.detail(traceId));
    }

    @GetMapping("/recent")
    public ApiResponse<List<RewriteTrace>> recent(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(rewriteService.recent(limit));
    }
}
