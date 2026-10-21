package com.raglab.eval.model;

/**
 * 一条被检索出来的「单元」。
 *
 * 为什么叫单元而不是切片：
 *   前三种策略的单元是切片（CHUNK），
 *   阶段五（CONTEXT）的单元是句子（SENTENCE）—— 粒度不一样，但都能排个名次，
 *   所以用同一个结构承载，指标口径才可比。
 */
public record EvalUnit(
        int rank,
        String kind,
        String text,
        long docId,
        String docName,
        int chunkIndex,
        String heading,
        double score
) {
    /** 只保留前 300 字进 JSON，避免结果集太大 */
    public EvalUnit compact() {
        String t = text == null ? "" : text;
        return new EvalUnit(rank, kind, t.length() <= 300 ? t : t.substring(0, 300) + "…",
                docId, docName, chunkIndex, heading, score);
    }
}
