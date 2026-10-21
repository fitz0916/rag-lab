package com.raglab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全部可调参数集中在这里，改 application.yml 即可，不用动代码。
 */
@ConfigurationProperties(prefix = "raglab")
public record RagLabProperties(Embedding embedding, Chroma chroma, Chunk chunk, Log log) {

    /**
     * 向量模型配置（Qwen / DashScope OpenAI 兼容模式）
     */
    public record Embedding(
            String baseUrl,
            String apiKey,
            String model,
            int dimensions,
            int batchSize,
            int timeoutSeconds,
            int maxRetries
    ) {
    }

    /**
     * Chroma 向量库配置
     */
    public record Chroma(
            String baseUrl,
            String tenant,
            String database,
            String collection,
            int timeoutSeconds,
            int writeBatchSize
    ) {
        /** v2 REST 的集合根路径 */
        public String collectionsPath() {
            return "/api/v2/tenants/%s/databases/%s/collections".formatted(tenant, database);
        }
    }

    /**
     * 切片参数（对应文章「每个片段控制在 200-500 字」）
     */
    public record Chunk(int size, int overlap, int minSize) {
    }

    /**
     * 运行日志配置
     *
     * @param retentionDays 日志保留天数，<=0 表示永久保留（不推荐，表会越来越大）
     */
    public record Log(int retentionDays) {
        public int effectiveRetentionDays() {
            return retentionDays;
        }
    }
}
