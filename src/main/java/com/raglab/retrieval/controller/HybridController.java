package com.raglab.retrieval.controller;

import com.raglab.common.ApiResponse;
import com.raglab.ingest.log.IngestLogBus;
import com.raglab.retrieval.dao.HybridTraceDao;
import com.raglab.retrieval.model.HybridTrace;
import com.raglab.retrieval.service.HybridService;
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
@RequestMapping("/api/hybrid")
public class HybridController {

    public record HybridRequest(String query, Integer vecTopN, Integer bm25TopN,
                                Integer finalTopK, Integer rrfK) {
    }

    private final HybridService hybridService;
    private final HybridTraceDao traceDao;
    private final IngestLogBus logBus;

    public HybridController(HybridService hybridService, HybridTraceDao traceDao, IngestLogBus logBus) {
        this.hybridService = hybridService;
        this.traceDao = traceDao;
        this.logBus = logBus;
    }

    @PostMapping
    public ApiResponse<Map<String, String>> submit(@RequestBody HybridRequest req) {
        String traceId = hybridService.submit(req.query(), req.vecTopN(), req.bm25TopN(),
                req.finalTopK(), req.rrfK());
        return ApiResponse.ok(Map.of("traceId", traceId));
    }

    @GetMapping(value = "/{traceId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String traceId) {
        HybridTrace trace = traceDao.findById(traceId)
                .orElseThrow(() -> new IllegalArgumentException("检索记录不存在：" + traceId));
        return logBus.subscribe(traceId, trace.finished());
    }

    @GetMapping("/{traceId}")
    public ApiResponse<HybridService.HybridDetail> detail(@PathVariable String traceId) {
        return ApiResponse.ok(hybridService.detail(traceId));
    }

    @GetMapping("/recent")
    public ApiResponse<List<HybridTrace>> recent(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(traceDao.listRecent(limit));
    }
}
