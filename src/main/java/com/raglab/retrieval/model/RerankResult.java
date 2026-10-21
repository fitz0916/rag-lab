package com.raglab.retrieval.model;

import java.util.List;

/**
 * 重排结果。
 *
 * 关键字段是 rankDelta（召回排名 - 重排后排名）：
 *   正数 = 被重排模型「捞」上来了，这是阶段四真正做的事
 *   负数 = 被压下去了
 * 前端靠这个画「↑12」「↓3」。
 *
 * dropped 单独列出来，是为了让「重排前进了 Top-K、重排后掉出去」的那些也看得见 ——
 * 只看最终结果的话，就不知道重排到底改变了什么。
 */
public record RerankResult(
        List<Hit> selected,
        List<Hit> dropped,
        int recallCount,
        int scoredCount
) {

    /**
     * @param finalRank   重排后排名（1 开始）；被淘汰的为 0
     * @param recallRank  召回时排名（1 开始）
     * @param rankDelta   recallRank - finalRank，正数表示上升
     * @param rerankScore 重排模型给的相关性分（0~1）
     * @param rrfScore    召回阶段的 RRF 分，用来看「重排前它凭什么进来的」
     */
    public record Hit(
            int finalRank,
            int recallRank,
            int rankDelta,
            double rerankScore,
            double rrfScore,
            int vecRank,
            int bm25Rank,
            String chromaId,
            long docId,
            String docName,
            int chunkIndex,
            String heading,
            String content,
            int charCount
    ) {
    }
}
