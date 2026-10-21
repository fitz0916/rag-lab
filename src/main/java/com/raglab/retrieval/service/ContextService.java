package com.raglab.retrieval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raglab.config.RagLabProperties;
import com.raglab.retrieval.dao.ContextTraceDao;
import com.raglab.retrieval.model.ContextResult;
import com.raglab.retrieval.model.ContextTrace;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ContextService {

    public record ContextDetail(ContextTrace trace, ContextResult result) {
    }

    private final ContextTraceDao traceDao;
    private final ContextRunner runner;
    private final ObjectMapper objectMapper;
    private final RagLabProperties props;

    public ContextService(ContextTraceDao traceDao, ContextRunner runner,
                          ObjectMapper objectMapper, RagLabProperties props) {
        this.traceDao = traceDao;
        this.runner = runner;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    public String submit(String query, Integer recallTopN, Integer topK, String strategy, Integer maxChars) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        String q = query.strip();
        int rn = (recallTopN == null || recallTopN <= 0) ? 20 : recallTopN;
        int k = (topK == null || topK <= 0) ? 5 : topK;
        String st = (strategy == null || strategy.isBlank())
                ? props.context().strategy() : strategy.toUpperCase();
        int mc = (maxChars == null || maxChars <= 0) ? props.context().maxChars() : maxChars;

        String traceId = UUID.randomUUID().toString();
        traceDao.insert(traceId, q, rn, st, mc);
        runner.run(traceId, q, rn, k, st, mc);
        return traceId;
    }

    public ContextDetail detail(String traceId) {
        ContextTrace trace = traceDao.findById(traceId)
                .orElseThrow(() -> new IllegalArgumentException("记录不存在：" + traceId));
        ContextResult result = null;
        String json = traceDao.findResultJson(traceId);
        if (json != null && !json.isBlank()) {
            try {
                result = objectMapper.readValue(json, ContextResult.class);
            } catch (Exception e) {
                throw new IllegalStateException("上下文结果反序列化失败", e);
            }
        }
        if (result == null) {
            result = new ContextResult(List.of(), List.of(), "", null);
        }
        return new ContextDetail(trace, result);
    }
}
