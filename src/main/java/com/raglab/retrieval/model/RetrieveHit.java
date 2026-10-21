package com.raglab.retrieval.model;

/**
 * 一条命中。
 *
 * distance 和 similarity 都留着，因为它们回答的是不同的问题：
 *   - distance   是向量库的原始输出（cosine 距离，越小越近）
 *   - similarity 是换成人能理解的「像不像」，= 1 - distance
 * 日志里两个都给，是为了让你看清这个换算过程。
 */
public record RetrieveHit(
        int rank,
        double distance,
        double similarity,
        String chromaId,
        long docId,
        String docName,
        int chunkIndex,
        String heading,
        String content,
        int charCount
) {
}
