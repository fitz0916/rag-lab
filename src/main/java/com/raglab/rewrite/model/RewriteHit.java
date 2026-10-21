package com.raglab.rewrite.model;

import java.util.List;

/**
 * 融合后的一条命中（阶段八）。
 *
 * from 是这个阶段最有信息量的字段：它记录「哪几路查询召回了这一条」。
 *   0      = 原查询
 *   1..n   = 第 n 个改写变体
 * 一条只在某个变体里出现的，很可能是变体引入的噪音（也可能是它救回来的答案，要人工看一眼）；
 * 被多个变体同时命中的，说明这条的语义确实稳定。
 */
public record RewriteHit(
        int rank,
        String chromaId,
        long docId,
        String docName,
        int chunkIndex,
        String heading,
        String content,
        int charCount,
        List<Integer> from,
        int hitCount,
        double fusedScore,
        double rerankScore,
        int baselineRank
) {
    public RewriteHit withRank(int r, double fused, double rerank, int baseRank) {
        return new RewriteHit(r, chromaId, docId, docName, chunkIndex, heading, content,
                charCount, from, from.size(), fused, rerank, baseRank);
    }

    /** JSON 里不必存全文，前 400 字够看了 */
    public RewriteHit compact() {
        String t = content == null ? "" : content;
        return new RewriteHit(rank, chromaId, docId, docName, chunkIndex, heading,
                t.length() <= 400 ? t : t.substring(0, 400) + "…", charCount, from, hitCount,
                fusedScore, rerankScore, baselineRank);
    }
}
