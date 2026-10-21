package com.raglab.retrieval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raglab.common.ExternalServiceException;
import com.raglab.config.RagLabProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重排序客户端（阶段四）。
 *
 * 用的是 DashScope 原生的 rerank 接口，**不是** OpenAI 兼容协议 ——
 * 路径是 /api/v1/services/rerank/text-rerank/text-rerank，
 * 请求体也是 DashScope 自己的结构（input.query + input.documents）。
 *
 * 它和向量检索的本质区别：
 *   向量：query 和 doc 分别编码成两个向量，算距离（双塔，快但粗）
 *   rerank：把 query 和 doc 拼成一对送进模型，看词与词的交互（交叉编码器，慢但准）
 *
 * 实测效果（问「Python 3.10 有什么新特性」）：
 *   "Python 3.10 引入了 match-case"     → 0.5287   ← 对的
 *   "Python 3.11 性能提升 10-60%"       → 0.1857   ← 分得清！
 *   "今天晚饭吃什么"                     → 0.0059
 *
 * 这正是向量检索做不到的：3.10 和 3.11 在语义空间里几乎重合。
 */
@Component
public class RerankClient {

    private static final Logger log = LoggerFactory.getLogger(RerankClient.class);

    /** 单条文档送模型的字符上限，防止超 token 限制 */
    private static final int MAX_DOC_CHARS = 4000;
    /** 单次请求最多送多少条 */
    private static final int HARD_MAX_DOCS = 200;

    private final RagLabProperties.Rerank cfg;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String apiKey;

    public RerankClient(RagLabProperties props, ObjectMapper objectMapper) {
        this.cfg = props.rerank();
        this.objectMapper = objectMapper;
        // 和 QwenEmbeddingClient 同样的兜底逻辑：.env 里 QWEN_API_KEY 留空就回退环境变量
        this.apiKey = resolveApiKey(props.embedding().apiKey());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(cfg.timeoutSeconds()));
        this.restClient = RestClient.builder()
                .baseUrl(cfg.baseUrl())
                .requestFactory(factory)
                .build();
    }

    private static String resolveApiKey(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String env = System.getenv("DASHSCOPE_API_KEY");
        return env == null ? "" : env.trim();
    }

    public String modelName() {
        return cfg.model();
    }

    public int maxDocuments() {
        return Math.min(cfg.maxDocuments(), HARD_MAX_DOCS);
    }

    public boolean hasApiKey() {
        return !apiKey.isBlank();
    }

    /**
     * @param index     对应输入 documents 的下标
     * @param relevance 相关性分（0~1，越大越相关）
     */
    public record Score(int index, double relevance) {
    }

    public record Result(List<Score> scores, int tokens, long costMs) {
    }

    /**
     * 给 (query, documents) 逐条打相关性分。
     *
     * 注意返回结果**不保证按分数排序**，而且不一定包含全部文档
     * （top_n 会截断），所以调用方要按 index 自己对齐。
     */
    public Result rerank(String query, List<String> documents) {
        if (documents.isEmpty()) {
            return new Result(List.of(), 0, 0);
        }
        if (!hasApiKey()) {
            throw new IllegalStateException(
                    "没有可用的 API Key：请把 key 填到 .env 的 QWEN_API_KEY，或设置 DASHSCOPE_API_KEY");
        }
        int n = Math.min(documents.size(), maxDocuments());
        List<String> docs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String d = documents.get(i);
            docs.add(d.length() > MAX_DOC_CHARS ? d.substring(0, MAX_DOC_CHARS) : d);
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", query);
        input.put("documents", docs);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("return_documents", false);
        params.put("top_n", n);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        body.put("input", input);
        body.put("parameters", params);

        long t0 = System.currentTimeMillis();
        int attempts = 3;
        Exception last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return doCall(body, docs.size(), t0);
            } catch (Exception e) {
                last = e;
                // 鉴权错了重试多少次都没用，直接抛
                if (e instanceof org.springframework.web.client.RestClientResponseException http
                        && (http.getStatusCode().value() == 401 || http.getStatusCode().value() == 403)) {
                    break;
                }
                if (attempt < attempts) {
                    log.warn("rerank 第 {}/{} 次调用失败（{}），{}ms 后重试",
                            attempt, attempts, e.getMessage(), 800L * attempt);
                    try {
                        Thread.sleep(800L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw new ExternalServiceException("DashScope-Rerank",
                "重排失败（model=%s, %d 条候选, 已重试 %d 次）".formatted(cfg.model(), docs.size(), attempts - 1),
                last);
    }

    private Result doCall(Map<String, Object> body, int docCount, long t0) throws Exception {
        String raw = restClient.post()
                .uri(cfg.endpoint())
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        JsonNode root = objectMapper.readTree(raw == null ? "{}" : raw);
        JsonNode results = root.path("output").path("results");
        if (!results.isArray()) {
            throw new IllegalStateException("rerank 响应里没有 output.results，原始响应：" + truncate(raw));
        }
        List<Score> scores = new ArrayList<>(results.size());
        for (JsonNode r : results) {
            scores.add(new Score(r.path("index").asInt(-1), r.path("relevance_score").asDouble(0)));
        }
        int tokens = root.path("usage").path("total_tokens").asInt(0);
        return new Result(scores, tokens, System.currentTimeMillis() - t0);
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }
}
