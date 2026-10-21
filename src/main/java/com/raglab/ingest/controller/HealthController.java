package com.raglab.ingest.controller;

import com.raglab.common.ApiResponse;
import com.raglab.common.ErrorDiagnoser;
import com.raglab.config.RagLabProperties;
import com.raglab.ingest.service.ChromaClient;
import com.raglab.ingest.service.QwenEmbeddingClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 依赖连通性自检。页面顶部三盏灯读的就是这里：
 * MySQL / Chroma / Qwen，任何一个红了你不用翻日志就知道问题在哪一层。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final JdbcTemplate jdbc;
    private final ChromaClient chromaClient;
    private final QwenEmbeddingClient embeddingClient;
    private final RagLabProperties props;

    public HealthController(JdbcTemplate jdbc, ChromaClient chromaClient,
                            QwenEmbeddingClient embeddingClient, RagLabProperties props) {
        this.jdbc = jdbc;
        this.chromaClient = chromaClient;
        this.embeddingClient = embeddingClient;
        this.props = props;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mysql", mysqlCheck());
        out.put("chroma", chromaCheck());
        out.put("qwen", qwenConfigCheck());
        out.put("config", Map.of(
                "chromaCollection", props.chroma().collection(),
                "chromaBaseUrl", props.chroma().baseUrl(),
                "embeddingModel", props.embedding().model(),
                "dimensions", props.embedding().dimensions(),
                "chunkSize", props.chunk().size(),
                "chunkOverlap", props.chunk().overlap(),
                "generateModel", props.generate() == null ? "" : props.generate().model(),
                "rerankModel", props.rerank() == null ? "" : props.rerank().model()
        ));
        return ApiResponse.ok(out);
    }

    /** 真的调一次 Qwen，验证 Key、网络、模型名、维度是否都对 */
    @PostMapping("/qwen-probe")
    public ApiResponse<Map<String, Object>> probeQwen() {
        Map<String, Object> out = new LinkedHashMap<>();
        long t = System.currentTimeMillis();
        try {
            QwenEmbeddingClient.BatchResult r = embeddingClient.embedBatch(List.of("连通性探测"));
            out.put("ok", true);
            out.put("dimensions", r.vectors().get(0).length);
            out.put("tokens", r.promptTokens());
            out.put("costMs", System.currentTimeMillis() - t);
            out.put("sample", List.of(
                    round(r.vectors().get(0)[0]), round(r.vectors().get(0)[1]), round(r.vectors().get(0)[2])));
            return ApiResponse.ok(out);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("costMs", System.currentTimeMillis() - t);
            out.put("diagnosis", ErrorDiagnoser.diagnose(e, "Qwen", "连通性探测失败"));
            return new ApiResponse<>(false, out, "Qwen 探测失败：" + e.getMessage());
        }
    }

    private Map<String, Object> mysqlCheck() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            String v = jdbc.queryForObject("SELECT VERSION()", String.class);
            Integer tables = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'rag_lab'", Integer.class);
            m.put("ok", true);
            m.put("detail", "MySQL " + v + " · rag_lab 库 " + tables + " 张表");
        } catch (Exception e) {
            m.put("ok", false);
            m.put("detail", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return m;
    }

    private Map<String, Object> chromaCheck() {
        Map<String, Object> m = new LinkedHashMap<>();
        long t = System.currentTimeMillis();
        boolean ok = chromaClient.heartbeat();
        m.put("ok", ok);
        m.put("detail", ok
                ? "Chroma 在线 · " + chromaClient.baseUrl()
                : "连不上 " + chromaClient.baseUrl() + "（检查容器是否启动、端口是否一致）");
        m.put("costMs", System.currentTimeMillis() - t);
        return m;
    }

    private Map<String, Object> qwenConfigCheck() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean hasKey = embeddingClient.hasApiKey();
        m.put("ok", hasKey);
        m.put("detail", hasKey
                ? embeddingClient.modelName() + " · " + embeddingClient.dimensions() + " 维 · Key 已配置"
                : "DASHSCOPE_API_KEY 未配置");
        return m;
    }

    private double round(float f) {
        return Math.round(f * 10000.0) / 10000.0;
    }
}
