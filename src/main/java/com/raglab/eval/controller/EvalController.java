package com.raglab.eval.controller;

import com.raglab.common.ApiResponse;
import com.raglab.eval.dao.EvalRunDao;
import com.raglab.eval.model.EvalCase;
import com.raglab.eval.model.EvalRun;
import com.raglab.eval.service.EvalService;
import com.raglab.ingest.log.IngestLogBus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/eval")
public class EvalController {

    public record RunRequest(List<Long> caseIds, String strategies, Integer topK, Integer recallTopN) {
    }

    public record CaseRequest(String name, String query, String expectText, String difficulty) {
    }

    private final EvalService evalService;
    private final EvalRunDao runDao;
    private final IngestLogBus logBus;

    public EvalController(EvalService evalService, EvalRunDao runDao, IngestLogBus logBus) {
        this.evalService = evalService;
        this.runDao = runDao;
        this.logBus = logBus;
    }

    // ---------------- 测试集 ----------------

    @GetMapping("/cases")
    public ApiResponse<List<EvalCase>> cases(@RequestParam(defaultValue = "false") boolean onlyEnabled) {
        return ApiResponse.ok(evalService.listCases(onlyEnabled));
    }

    @PostMapping("/cases")
    public ApiResponse<Map<String, Object>> addCase(@RequestBody CaseRequest req) {
        long id = evalService.addCase(req.name(), req.query(), req.expectText(), req.difficulty());
        return ApiResponse.ok(Map.of("id", id));
    }

    @PostMapping("/cases/{id}/toggle")
    public ApiResponse<Void> toggle(@PathVariable long id, @RequestParam boolean enabled) {
        evalService.toggleCase(id, enabled);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/cases/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        evalService.deleteCase(id);
        return ApiResponse.ok(null);
    }

    // ---------------- 评测运行 ----------------

    @PostMapping("/run")
    public ApiResponse<Map<String, String>> run(@RequestBody RunRequest req) {
        String runId = evalService.submit(req.caseIds(), req.strategies(), req.topK(), req.recallTopN());
        return ApiResponse.ok(Map.of("runId", runId));
    }

    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String runId) {
        EvalRun run = runDao.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("评测记录不存在：" + runId));
        return logBus.subscribe(runId, run.finished());
    }

    @GetMapping("/{runId}")
    public ApiResponse<EvalService.EvalDetail> detail(@PathVariable String runId) {
        return ApiResponse.ok(evalService.detail(runId));
    }

    @GetMapping("/runs")
    public ApiResponse<List<EvalRun>> recent(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(runDao.listRecent(Math.max(1, Math.min(limit, 50))));
    }
}
