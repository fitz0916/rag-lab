package com.raglab.retrieval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raglab.retrieval.dao.RerankTraceDao;
import com.raglab.retrieval.model.RerankResult;
import com.raglab.retrieval.model.RerankTrace;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class RerankService {

    public record RerankDetail(RerankTrace trace, RerankResult result) {
    }

    private final RerankTraceDao traceDao;
    private final RerankRunner runner;
    private final ObjectMapper objectMapper;

    public RerankService(RerankTraceDao traceDao, RerankRunner runner, ObjectMapper objectMapper) {
        this.traceDao = traceDao;
        this.runner = runner;
        this.objectMapper = objectMapper;
    }

    public String submit(String query, Integer recallTopN, Integer finalTopK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        String q = query.strip();
        int rn = (recallTopN == null || recallTopN <= 0) ? 20 : recallTopN;
        int fk = (finalTopK == null || finalTopK <= 0) ? 5 : finalTopK;

        String traceId = UUID.randomUUID().toString();
        traceDao.insert(traceId, q, rn, fk);
        runner.run(traceId, q, rn, fk);
        return traceId;
    }

    public RerankDetail detail(String traceId) {
        RerankTrace trace = traceDao.findById(traceId)
                .orElseThrow(() -> new IllegalArgumentException("检索记录不存在：" + traceId));
        RerankResult result = null;
        String json = traceDao.findResultJson(traceId);
        if (json != null && !json.isBlank()) {
            try {
                result = objectMapper.readValue(json, RerankResult.class);
            } catch (Exception e) {
                throw new IllegalStateException("重排结果反序列化失败", e);
            }
        }
        if (result == null) {
            result = new RerankResult(List.of(), List.of(), 0, 0);
        }
        return new RerankDetail(trace, result);
    }
}
