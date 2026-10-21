package com.raglab.retrieval.controller;

import com.raglab.common.ApiResponse;
import com.raglab.ingest.log.IngestLogBus;
import com.raglab.retrieval.dao.RerankTraceDao;
import com.raglab.retrieval.model.RerankTrace;
import com.raglab.retrieval.service.RerankService;
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
@RequestMapping("/api/rerank")
public class RerankController {

    public record RerankRequest(String query, Integer recallTopN, Integer finalTopK) {
    }

    private final RerankService rerankService;
    private final RerankTraceDao traceDao;
    private final IngestLogBus logBus;

    public RerankController(RerankService rerankService, RerankTraceDao traceDao, IngestLogBus logBus) {
        this.rerankService = rerankService;
        this.traceDao = traceDao;
        this.logBus = logBus;
    }

    @PostMapping
    public ApiResponse<Map<String, String>> submit(@RequestBody RerankRequest req) {
        String traceId = rerankService.submit(req.query(), req.recallTopN(), req.finalTopK());
        return ApiResponse.ok(Map.of("traceId", traceId));
    }

    @GetMapping(value = "/{traceId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String traceId) {
        RerankTrace trace = traceDao.findById(traceId)
                .orElseThrow(() -> new IllegalArgumentException("检索记录不存在：" + traceId));
        return logBus.subscribe(traceId, trace.finished());
    }

    @GetMapping("/{traceId}")
    public ApiResponse<RerankService.RerankDetail> detail(@PathVariable String traceId) {
        return ApiResponse.ok(rerankService.detail(traceId));
    }

    @GetMapping("/recent")
    public ApiResponse<List<RerankTrace>> recent(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(traceDao.listRecent(limit));
    }
}
