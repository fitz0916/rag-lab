package com.raglab.ingest.service;

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
 * Qwen 向量化客户端（DashScope OpenAI 兼容模式）。
 *
 * 关键约束（都来自 DashScope 的接口限制，踩过一次就记住了）：
 * - 单次请求最多 10 条文本 → batch-size 必须 ≤10，超了会被 400 拒掉
 * - 返回的 data 数组不保证按输入顺序 → 必须按 index 字段重新排序，否则向量和文本会错位
 * - 单条文本上限 8192 token → 超长文本先截断
 */
@Component
public class QwenEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(QwenEmbeddingClient.class);
    /** 单条文本上限（text-embedding-v4） */
    private static final int MAX_CHARS_PER_TEXT = 6000;

    private final RagLabProperties.Embedding cfg;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    /** 实际使用的 key（已处理 .env 留空的情况） */
    private final String apiKey;

    public QwenEmbeddingClient(RagLabProperties props, ObjectMapper objectMapper) {
        this.cfg = props.embedding();
        this.objectMapper = objectMapper;
        this.apiKey = resolveApiKey(cfg.apiKey());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(cfg.timeoutSeconds()));
        this.restClient = RestClient.builder()
                .baseUrl(cfg.baseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * .env 里写 QWEN_API_KEY= （留空）时，Spring 会把它解析成空字符串，
     * 而 ${A:B} 的默认值只在 A 完全不存在时生效，空串不会回退到 DASHSCOPE_API_KEY。
     * 所以这里手动兜一层：空白就去找系统环境变量。
     */
    private static String resolveApiKey(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String env = System.getenv("DASHSCOPE_API_KEY");
        return env == null ? "" : env.trim();
    }

    /** 一批的结果 */
    public record BatchResult(List<float[]> vectors, int promptTokens, long costMs) {
    }

    public boolean hasApiKey() {
        return !apiKey.isBlank();
    }

    public String modelName() {
        return cfg.model();
    }

    public int dimensions() {
        return cfg.dimensions();
    }

    /**
     * 向量化一批文本（调用方负责保证 size ≤ batch-size）。
     */
    public BatchResult embedBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return new BatchResult(List.of(), 0, 0);
        }
        if (texts.size() > cfg.batchSize()) {
            throw new IllegalArgumentException(
                    "这批文本 " + texts.size() + " 条，超过单批上限 " + cfg.batchSize() + " 条");
        }
        if (!hasApiKey()) {
            throw new IllegalStateException(
                    "没有可用的 Qwen API Key：请把 key 填到项目根目录 .env 的 QWEN_API_KEY，"
                            + "或者设置为系统环境变量 DASHSCOPE_API_KEY");
        }

        List<String> inputs = new ArrayList<>(texts.size());
        for (String t : texts) {
            inputs.add(t.length() > MAX_CHARS_PER_TEXT ? t.substring(0, MAX_CHARS_PER_TEXT) : t);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        body.put("input", inputs);
        body.put("dimensions", cfg.dimensions());
        body.put("encoding_format", "float");

        long t0 = System.currentTimeMillis();
        Exception last = null;
        for (int attempt = 0; attempt <= cfg.maxRetries(); attempt++) {
            try {
                String raw = restClient.post()
                        .uri("/embeddings")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
                return parse(raw, texts.size(), System.currentTimeMillis() - t0);
            } catch (Exception ex) {
                last = ex;
                if (!retryable(ex) || attempt == cfg.maxRetries()) {
                    break;
                }
                long backoff = 1000L * (attempt + 1);
                log.warn("Qwen 向量化第 {} 次失败，{}ms 后重试: {}", attempt + 1, backoff, ex.getMessage());
                sleep(backoff);
            }
        }
        throw new ExternalServiceException("Qwen",
                "调用 Qwen 向量模型失败（model=" + cfg.model() + ", 本批 " + texts.size() + " 条）", last);
    }

    private BatchResult parse(String raw, int expected, long costMs) throws Exception {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Qwen 返回空响应体");
        }
        JsonNode root = objectMapper.readTree(raw);
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("Qwen 响应里没有 data 数组，原始响应：" + truncate(raw));
        }
        // 按 index 回填，防止顺序错位
        float[][] slot = new float[expected][];
        for (JsonNode item : data) {
            int idx = item.path("index").asInt(0);
            JsonNode emb = item.path("embedding");
            if (emb.isMissingNode() || !emb.isArray()) {
                throw new IllegalStateException("第 " + idx + " 条没有 embedding 字段，原始响应：" + truncate(raw));
            }
            if (emb.size() != cfg.dimensions()) {
                throw new IllegalStateException("向量维度不符：期望 " + cfg.dimensions()
                        + "，实际 " + emb.size() + "（检查 raglab.embedding.dimensions 与模型是否匹配）");
            }
            float[] v = new float[emb.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) emb.get(i).asDouble();
            }
            if (idx >= 0 && idx < expected) {
                slot[idx] = v;
            }
        }
        List<float[]> vectors = new ArrayList<>(expected);
        for (int i = 0; i < expected; i++) {
            if (slot[i] == null) {
                throw new IllegalStateException("Qwen 少返回了第 " + i + " 条的向量（期望 " + expected
                        + " 条，实际 " + data.size() + " 条）");
            }
            vectors.add(slot[i]);
        }
        int tokens = root.path("usage").path("total_tokens").asInt(0);
        return new BatchResult(vectors, tokens, costMs);
    }

    /** 只对网络抖动和 5xx/429 重试；401/400 这类重试也没用 */
    private boolean retryable(Exception ex) {
        Throwable t = ex;
        while (t != null) {
            if (t instanceof org.springframework.web.client.RestClientResponseException r) {
                int code = r.getStatusCode().value();
                return code == 429 || code >= 500;
            }
            if (t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.ConnectException
                    || t instanceof org.springframework.web.client.ResourceAccessException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }
}
