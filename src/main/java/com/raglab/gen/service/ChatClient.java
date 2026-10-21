package com.raglab.gen.service;

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
 * 对话模型客户端（阶段七）。
 *
 * 走 DashScope 的 OpenAI 兼容协议：POST /chat/completions，
 * 和 embedding 是同一个域名、同一个 API Key，只是 model 名不同。
 *
 * 为什么这里不用「原生接口」？
 *   rerank 没有 OpenAI 兼容形态，只能走原生；而 chat 有兼容形态，
 *   用兼容形态的好处是将来换成 DeepSeek / 通义国际版 / 本地 vLLM，
 *   只改 baseUrl + model，代码一行不动。
 */
@Component
public class ChatClient {

    private static final Logger log = LoggerFactory.getLogger(ChatClient.class);

    private final RagLabProperties.Generate cfg;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String apiKey;

    public ChatClient(RagLabProperties props, ObjectMapper objectMapper) {
        this.cfg = props.generate();
        this.objectMapper = objectMapper;
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

    public boolean hasApiKey() {
        return !apiKey.isBlank();
    }

    public record Usage(int promptTokens, int completionTokens) {
    }

    public record Result(String content, Usage usage, long costMs, String finishReason) {
    }

    /**
     * @param system 系统提示（角色 + 约束）
     * @param user   用户消息（资料 + 问题）
     */
    public Result chat(String system, String user) {
        return chat(system, user, cfg.temperature());
    }

    /**
     * 指定 temperature 的版本 —— 查询重写要用高一点的温度（0.7）才能生成多样的表达，
     * 而 RAG 问答要低温度（0.2）保证忠于资料。同一个客户端，两种用途。
     */
    public Result chat(String system, String user, double temperature) {
        if (!hasApiKey()) {
            throw new IllegalStateException(
                    "没有可用的 API Key：把 key 填到 .env 的 QWEN_API_KEY，或设置 DASHSCOPE_API_KEY");
        }

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        messages.add(Map.of("role", "user", "content", user));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        body.put("messages", messages);
        body.put("temperature", temperature);
        body.put("max_tokens", cfg.maxTokens());

        long t0 = System.currentTimeMillis();
        int attempts = 2;
        Exception last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return doCall(body, t0);
            } catch (Exception e) {
                last = e;
                if (e instanceof org.springframework.web.client.RestClientResponseException http
                        && (http.getStatusCode().value() == 401 || http.getStatusCode().value() == 403)) {
                    break;
                }
                if (attempt < attempts) {
                    log.warn("chat 第 {}/{} 次失败（{}），1.2s 后重试", attempt, attempts, e.getMessage());
                    try {
                        Thread.sleep(1200);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw new ExternalServiceException("DashScope-Chat",
                "调用对话模型失败（model=%s）".formatted(cfg.model()), last);
    }

    private Result doCall(Map<String, Object> body, long t0) throws Exception {
        String raw = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        JsonNode root = objectMapper.readTree(raw == null ? "{}" : raw);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("chat 响应里没有 choices，原始响应："
                    + (raw == null ? "" : raw.substring(0, Math.min(400, raw.length()))));
        }
        JsonNode first = choices.get(0);
        String content = first.path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new IllegalStateException("模型返回了空内容，finish_reason="
                    + first.path("finish_reason").asText("?"));
        }
        JsonNode usage = root.path("usage");
        return new Result(content.strip(),
                new Usage(usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0)),
                System.currentTimeMillis() - t0,
                first.path("finish_reason").asText("stop"));
    }
}
