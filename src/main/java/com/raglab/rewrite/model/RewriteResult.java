package com.raglab.rewrite.model;

import java.util.List;

/**
 * 查询重写的完整结果（阶段八）。
 *
 * 三份数据，对应三个问题：
 *   variants —— 模型到底把问题改写成了什么？（改写质量靠人看）
 *   merged   —— 融合后的 Top-K（这是最终要用的）
 *   baseline —— 只用原查询的 Top-K（对照组，用来回答「改写到底有没有用」）
 *
 * 只给 merged 是不够的：没有 baseline，你无法判断某条结果是改写带来的，还是原本就有。
 */
public record RewriteResult(
        String original,
        List<Variant> variants,
        List<RewriteHit> merged,
        List<RewriteHit> baseline,
        Stats stats
) {

    /**
     * @param index   1..n，0 留给原查询
     * @param newHits 这个变体召回、但原查询没召回的条数 —— 衡量「它带来了多少新东西」
     */
    public record Variant(int index, String text, int recallCount, int newHits) {
    }

    public record Stats(
            int variantCount,
            int candidates,
            int mergedCount,
            int promptTokens,
            boolean reranked,
            int gainedInTopK,
            int lostFromTopK
    ) {
    }
}
