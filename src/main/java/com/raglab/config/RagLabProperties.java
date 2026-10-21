package com.raglab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全部可调参数集中在这里，改 application.yml 即可，不用动代码。
 */
@ConfigurationProperties(prefix = "raglab")
public record RagLabProperties(Embedding embedding, Chroma chroma, Chunk chunk,
                               Log log, Rerank rerank, Context context) {

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

    /**
     * 重排序配置（阶段四）—— 用的是 DashScope 原生的 rerank 接口，
     * 不是 OpenAI 兼容协议，所以 baseUrl 也不同。
     *
     * @param maxDocuments 单次最多送多少条给重排模型（多了会超 token 限制）
     */
    public record Rerank(String baseUrl, String model, int timeoutSeconds, int maxDocuments) {
        public String endpoint() {
            return "/api/v1/services/rerank/text-rerank/text-rerank";
        }
    }

    /**
     * 上下文控制配置（阶段五）
     *
     * @param strategy             VECTOR（句子级向量，准但慢） / KEYWORD（关键词密度，快但粗）
     * @param maxSentencesPerChunk 每个切片最多贡献几句，防止某一片把所有名额占满
     * @param maxChars             最终上下文长度上限
     * @param dedupThreshold       两句相似度超过这个值只留一条（overlap 会导致相邻切片重复）
     */
    public record Context(String strategy, int maxSentencesPerChunk, int maxChars, double dedupThreshold) {
    }
}
