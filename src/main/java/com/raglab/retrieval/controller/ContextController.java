package com.raglab.retrieval.controller;

import com.raglab.common.ApiResponse;
import com.raglab.ingest.log.IngestLogBus;
import com.raglab.retrieval.dao.ContextTraceDao;
import com.raglab.retrieval.model.ContextTrace;
import com.raglab.retrieval.service.ContextService;
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
@RequestMapping("/api/context")
public class ContextController {

    public record ContextRequest(String query, Integer recallTopN, Integer topK,
                                 String strategy, Integer maxChars) {
    }

    private final ContextService contextService;
    private final ContextTraceDao traceDao;
    private final IngestLogBus logBus;

    public ContextController(ContextService contextService, ContextTraceDao traceDao, IngestLogBus logBus) {
        this.contextService = contextService;
        this.traceDao = traceDao;
        this.logBus = logBus;
    }

    @PostMapping
    public ApiResponse<Map<String, String>> submit(@RequestBody ContextRequest req) {
        String traceId = contextService.submit(req.query(), req.recallTopN(), req.topK(),
                req.strategy(), req.maxChars());
        return ApiResponse.ok(Map.of("traceId", traceId));
    }

    @GetMapping(value = "/{traceId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String traceId) {
        ContextTrace trace = traceDao.findById(traceId)
                .orElseThrow(() -> new IllegalArgumentException("记录不存在：" + traceId));
        return logBus.subscribe(traceId, trace.finished());
    }

    @GetMapping("/{traceId}")
    public ApiResponse<ContextService.ContextDetail> detail(@PathVariable String traceId) {
        return ApiResponse.ok(contextService.detail(traceId));
    }

    @GetMapping("/recent")
    public ApiResponse<List<ContextTrace>> recent(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(traceDao.listRecent(limit));
    }
}
