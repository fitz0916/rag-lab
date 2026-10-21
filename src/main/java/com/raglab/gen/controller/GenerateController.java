package com.raglab.gen.controller;

import com.raglab.common.ApiResponse;
import com.raglab.gen.dao.GenerateTraceDao;
import com.raglab.gen.model.GenerateTrace;
import com.raglab.gen.service.GenerateService;
import com.raglab.ingest.log.IngestLogBus;
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
@RequestMapping("/api/generate")
public class GenerateController {

    public record GenerateRequest(String query, Integer recallTopN, Integer topK, Integer maxChars) {
    }

    private final GenerateService generateService;
    private final GenerateTraceDao traceDao;
    private final IngestLogBus logBus;

    public GenerateController(GenerateService generateService, GenerateTraceDao traceDao,
                              IngestLogBus logBus) {
        this.generateService = generateService;
        this.traceDao = traceDao;
        this.logBus = logBus;
    }

    @PostMapping
    public ApiResponse<Map<String, String>> submit(@RequestBody GenerateRequest req) {
        String traceId = generateService.submit(req.query(), req.recallTopN(), req.topK(), req.maxChars());
        return ApiResponse.ok(Map.of("traceId", traceId));
    }

    @GetMapping(value = "/{traceId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String traceId) {
        GenerateTrace trace = traceDao.findById(traceId)
                .orElseThrow(() -> new IllegalArgumentException("记录不存在：" + traceId));
        return logBus.subscribe(traceId, trace.finished());
    }

    @GetMapping("/{traceId}")
    public ApiResponse<GenerateService.GenerateDetail> detail(@PathVariable String traceId) {
        return ApiResponse.ok(generateService.detail(traceId));
    }

    @GetMapping("/recent")
    public ApiResponse<List<GenerateTrace>> recent(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(generateService.recent(limit));
    }
}
