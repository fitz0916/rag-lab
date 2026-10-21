package com.raglab.retrieval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raglab.retrieval.dao.HybridTraceDao;
import com.raglab.retrieval.model.HybridResult;
import com.raglab.retrieval.model.HybridTrace;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class HybridService {

    public record HybridDetail(HybridTrace trace, HybridResult result) {
    }

    private final HybridTraceDao traceDao;
    private final HybridRunner runner;
    private final ObjectMapper objectMapper;

    public HybridService(HybridTraceDao traceDao, HybridRunner runner, ObjectMapper objectMapper) {
        this.traceDao = traceDao;
        this.runner = runner;
        this.objectMapper = objectMapper;
    }

    public String submit(String query, Integer vecTopN, Integer bm25TopN, Integer finalTopK, Integer rrfK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        String q = query.strip();
        int vn = (vecTopN == null || vecTopN <= 0) ? 20 : vecTopN;
        int bn = (bm25TopN == null || bm25TopN <= 0) ? 20 : bm25TopN;
        int fk = (finalTopK == null || finalTopK <= 0) ? 5 : finalTopK;
        int k = (rrfK == null || rrfK <= 0) ? 60 : rrfK;

        String traceId = UUID.randomUUID().toString();
        traceDao.insert(traceId, q, vn, bn, fk, k);
        runner.run(traceId, q, vn, bn, fk, k);
        return traceId;
    }

    public HybridDetail detail(String traceId) {
        HybridTrace trace = traceDao.findById(traceId)
                .orElseThrow(() -> new IllegalArgumentException("检索记录不存在：" + traceId));
        HybridResult result = null;
        String json = traceDao.findResultJson(traceId);
        if (json != null && !json.isBlank()) {
            try {
                result = objectMapper.readValue(json, HybridResult.class);
            } catch (Exception e) {
                throw new IllegalStateException("融合结果反序列化失败", e);
            }
        }
        if (result == null) {
            result = new HybridResult(List.of(), List.of(), List.of());
        }
        return new HybridDetail(trace, result);
    }
}
