package com.raglab.rewrite.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raglab.config.RagLabProperties;
import com.raglab.rewrite.dao.RewriteTraceDao;
import com.raglab.rewrite.model.RewriteResult;
import com.raglab.rewrite.model.RewriteTrace;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class RewriteService {

    public record RewriteDetail(RewriteTrace trace, RewriteResult result) {
    }

    private final RewriteTraceDao traceDao;
    private final RewriteRunner runner;
    private final ObjectMapper objectMapper;
    private final RagLabProperties props;

    public RewriteService(RewriteTraceDao traceDao, RewriteRunner runner,
                          ObjectMapper objectMapper, RagLabProperties props) {
        this.traceDao = traceDao;
        this.runner = runner;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    public String submit(String query, Integer variants, Integer topK, Integer recallN, Boolean rerank) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        String q = query.strip();
        if (q.length() > 500) {
            throw new IllegalArgumentException("问题过长（" + q.length() + " 字），请精简到 500 字以内");
        }
        int v = (variants == null || variants <= 0) ? props.rewrite().variants() : Math.min(variants, 5);
        int k = (topK == null || topK <= 0) ? 5 : Math.min(topK, 20);
        int rn = (recallN == null || recallN <= 0) ? 20 : Math.min(recallN, 50);
        boolean rr = rerank == null ? props.rewrite().rerank() : rerank;

        String traceId = UUID.randomUUID().toString();
        traceDao.insert(traceId, q, k, rn, rr);
        runner.run(traceId, q, v, k, rn, rr);
        return traceId;
    }

    public RewriteDetail detail(String traceId) {
        RewriteTrace trace = traceDao.findById(traceId)
                .orElseThrow(() -> new IllegalArgumentException("记录不存在：" + traceId));
        RewriteResult result = null;
        String json = traceDao.findResultJson(traceId);
        if (json != null && !json.isBlank()) {
            try {
                result = objectMapper.readValue(json, RewriteResult.class);
            } catch (Exception e) {
                throw new IllegalStateException("重写结果反序列化失败", e);
            }
        }
        return new RewriteDetail(trace, result);
    }

    public List<RewriteTrace> recent(int limit) {
        return traceDao.listRecent(Math.max(1, Math.min(limit, 50)));
    }
}
