package com.raglab.retrieval.model;

import java.util.List;

/**
 * 上下文控制结果（阶段五）。
 *
 * 三份数据：
 *   sources   —— 重排后的源切片（压缩前），用来看「压缩前有多少东西」
 *   sentences —— 选中的句子（按原文顺序），用来看「留下了什么」
 *   context   —— 拼好的上下文，这才是最终喂给大模型的东西
 *
 * 关键指标是 compressRatio：0.3 表示砍掉了 70% 的内容。
 */
public record ContextResult(
        List<SourceChunk> sources,
        List<Sentence> sentences,
        String context,
        Stats stats
) {

    /** 压缩前的源切片 */
    public record SourceChunk(
            int rank,
            String chromaId,
            long docId,
            String docName,
            int chunkIndex,
            String heading,
            double rerankScore,
            String content,
            int charCount,
            int sentenceCount,
            int selectedCount
    ) {
    }

    /**
     * 选中的一句。
     *
     * @param order      在最终上下文里的顺序（1 开始，按原文顺序，不是按分数）
     * @param score      相关性分（VECTOR 策略是余弦相似度，KEYWORD 策略是覆盖率综合分）
     * @param sourceRank 来自第几片（重排后的排名）
     */
    public record Sentence(
            int order,
            String text,
            String chromaId,
            long docId,
            String docName,
            int chunkIndex,
            String heading,
            int sourceRank,
            double score,
            int charCount
    ) {
    }

    public record Stats(
            int originChars,
            int finalChars,
            int originTokens,
            int finalTokens,
            double compressRatio,
            int totalSentences,
            int selectedSentences,
            int droppedDuplicates,
            int droppedByLimit
    ) {
    }
}
