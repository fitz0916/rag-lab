package com.raglab.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raglab.common.ApiResponse;
import com.raglab.gen.service.ChatClient;
import com.raglab.ingest.service.QwenEmbeddingClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 缓存观测与运维接口（阶段九）。
 *
 * 缓存最怕「不知道它在干什么」：
 *   命中率多少？哪一层在起作用？到底是缓存返回的，还是真的重新算了一遍？
 * 所以这里把三层状态、命中详情、手动清空都暴露出来。
 */
@RestController
@RequestMapping("/api/cache")
public class CacheController {

    public record LookupRequest(String query, Integer topK) {
    }

    public record LookupResponse(boolean hit, String layer, double similarity, long costMs,
                                 int answerChars, String preview) {
    }

    private final CacheService cache;
    private final QwenEmbeddingClient embeddingClient;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public CacheController(CacheService cache, QwenEmbeddingClient embeddingClient,
                           ChatClient chatClient, ObjectMapper objectMapper) {
        this.cache = cache;
        this.embeddingClient = embeddingClient;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        CacheService.Stats s = cache.stats();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stats", s);
        out.put("redis", cache.redisInfo());
        return ApiResponse.ok(out);
    }

    /**
     * 只查缓存，不生成。用来验证「现在这个问题到底有没有被缓存」。
     * 注意：L3 语义检查需要算 embedding，所以这里会调一次 Qwen（几百毫秒）。
     */
    @PostMapping("/lookup")
    public ApiResponse<LookupResponse> lookup(@RequestBody LookupRequest req) {
        if (req.query() == null || req.query().isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        int topK = (req.topK() == null || req.topK() <= 0) ? 5 : Math.min(req.topK(), 10);
        String query = req.query().strip();
        CacheService.Lookup look = cache.lookup("gen", query,
                new CacheService.Params(topK, chatClient.modelName()),
                () -> embeddingClient.embedBatch(List.of(query)).vectors().get(0));

        String preview = "";
        int chars = 0;
        if (look.hit() && look.payload() != null) {
            try {
                JsonNode node = objectMapper.readTree(look.payload());
                String answer = node.path("answer").asText("");
                chars = answer.length();
                preview = answer.length() <= 160 ? answer : answer.substring(0, 160) + "…";
            } catch (Exception ignored) {
                preview = "（缓存内容解析失败）";
            }
        }
        return ApiResponse.ok(new LookupResponse(look.hit(), look.layer(), look.similarity(),
                look.costMs(), chars, preview));
    }

    @DeleteMapping
    public ApiResponse<Map<String, Object>> clear() {
        int n = cache.clearAll("手动清空");
        return ApiResponse.ok(Map.of("cleared", n));
    }

    /**
     * 只清 L1（内存）。用来演示「L1 清了，还能命中 L2」——
     * 这是理解「多层」最直观的一个动作。
     */
    @DeleteMapping("/l1")
    public ApiResponse<Map<String, Object>> clearL1() {
        int n = cache.clearMemoryOnly();
        return ApiResponse.ok(Map.of("cleared", n));
    }
}
