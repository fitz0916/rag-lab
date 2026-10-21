package com.raglab.retrieval.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raglab.retrieval.dao.RetrieveTraceDao;
import com.raglab.retrieval.model.RetrieveHit;
import com.raglab.retrieval.model.RetrieveTrace;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 检索任务的提交与结果读取。
 *
 * 和入库一样：先写轨迹记录，再扔异步线程。
 * 这样前端拿到 traceId 立刻订阅 SSE 时，DB 里已经能查到这条轨迹（否则订阅会 404）。
 */
@Service
public class RetrieveService {

    public record RetrieveResult(RetrieveTrace trace, List<RetrieveHit> hits) {
    }

    private final RetrieveTraceDao traceDao;
    private final RetrieveRunner runner;
    private final ObjectMapper objectMapper;

    public RetrieveService(RetrieveTraceDao traceDao, RetrieveRunner runner, ObjectMapper objectMapper) {
        this.traceDao = traceDao;
        this.runner = runner;
        this.objectMapper = objectMapper;
    }

    public String submit(String query, Integer topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        String q = query.strip();
        int k = (topK == null || topK <= 0) ? 5 : topK;
        String traceId = UUID.randomUUID().toString();
        traceDao.insert(traceId, q, k);
        runner.run(traceId, q, k);
        return traceId;
    }

    public RetrieveResult detail(String traceId) {
        RetrieveTrace trace = traceDao.findById(traceId)
                .orElseThrow(() -> new IllegalArgumentException("检索记录不存在：" + traceId));
        List<RetrieveHit> hits = List.of();
        String json = traceDao.findHitsJson(traceId);
        if (json != null && !json.isBlank()) {
            try {
                hits = objectMapper.readValue(json, new TypeReference<List<RetrieveHit>>() {
                });
            } catch (Exception e) {
                throw new IllegalStateException("命中结果反序列化失败", e);
            }
        }
        return new RetrieveResult(trace, hits);
    }
}
