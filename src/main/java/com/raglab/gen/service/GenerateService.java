package com.raglab.gen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raglab.config.RagLabProperties;
import com.raglab.gen.dao.GenerateTraceDao;
import com.raglab.gen.model.GenerateResult;
import com.raglab.gen.model.GenerateTrace;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class GenerateService {

    public record GenerateDetail(GenerateTrace trace, GenerateResult result) {
    }

    private final GenerateTraceDao traceDao;
    private final GenerateRunner runner;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final RagLabProperties props;

    public GenerateService(GenerateTraceDao traceDao, GenerateRunner runner, ChatClient chatClient,
                           ObjectMapper objectMapper, RagLabProperties props) {
        this.traceDao = traceDao;
        this.runner = runner;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    public String submit(String query, Integer recallTopN, Integer topK, Integer maxChars) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        String q = query.strip();
        if (q.length() > 500) {
            throw new IllegalArgumentException("问题过长（" + q.length() + " 字），请精简到 500 字以内");
        }
        if (!chatClient.hasApiKey()) {
            throw new IllegalStateException("没有可用的 API Key：把 key 填到 .env 的 QWEN_API_KEY");
        }
        int rn = (recallTopN == null || recallTopN <= 0) ? 20 : recallTopN;
        int k = (topK == null || topK <= 0) ? 5 : topK;
        int mc = (maxChars == null || maxChars <= 0) ? props.context().maxChars() : maxChars;

        String traceId = UUID.randomUUID().toString();
        traceDao.insert(traceId, q, chatClient.modelName());
        runner.run(traceId, q, rn, k, mc);
        return traceId;
    }

    public GenerateDetail detail(String traceId) {
        GenerateTrace trace = traceDao.findById(traceId)
                .orElseThrow(() -> new IllegalArgumentException("记录不存在：" + traceId));
        GenerateResult result = null;
        String json = traceDao.findResultJson(traceId);
        if (json != null && !json.isBlank()) {
            try {
                result = objectMapper.readValue(json, GenerateResult.class);
            } catch (Exception e) {
                throw new IllegalStateException("生成结果反序列化失败", e);
            }
        }
        return new GenerateDetail(trace, result);
    }

    public List<GenerateTrace> recent(int limit) {
        return traceDao.listRecent(Math.max(1, Math.min(limit, 50)));
    }
}
