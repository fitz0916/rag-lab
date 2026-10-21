package com.raglab.retrieval.model;

import java.util.List;

/**
 * 混合检索结果：两路召回明细 + RRF 融合后的最终排名。
 *
 * 三份数据都返回给前端，是为了能做「三列对比」——
 * 一眼看出某一条在向量里排第 5、在 BM25 里排第 1、融合后升到第 1。
 * 只返回融合结果的话，就看不到「为什么升上来」了。
 */
public record HybridResult(
        List<Candidate> vectorHits,
        List<Candidate> bm25Hits,
        List<FusedHit> fused
) {

    /**
     * 单路召回的候选。
     *
     * @param score 向量路是相似度（1-distance），BM25 路是 BM25 分数 —— 两者不可比，这也是为什么用 RRF
     */
    public record Candidate(
            int rank,
            String chromaId,
            double score,
            long docId,
            String docName,
            int chunkIndex,
            String heading,
            String content,
            int charCount
    ) {
    }

    /**
     * 融合后的一条。
     *
     * @param vecRank   向量路排名，-1 表示这一路没召回它
     * @param bm25Rank  BM25 路排名，-1 表示这一路没召回它
     * @param source    BOTH / VECTOR_ONLY / BM25_ONLY
     */
    public record FusedHit(
            int rank,
            String chromaId,
            double rrfScore,
            int vecRank,
            double vecScore,
            int bm25Rank,
            double bm25Score,
            String source,
            long docId,
            String docName,
            int chunkIndex,
            String heading,
            String content,
            int charCount
    ) {
    }
}
