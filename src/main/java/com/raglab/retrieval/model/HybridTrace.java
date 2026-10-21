package com.raglab.retrieval.model;

import java.time.LocalDateTime;

/** 一次混合检索的轨迹 */
public record HybridTrace(
        String id,
        String queryText,
        int vecTopn,
        int bm25Topn,
        int finalTopk,
        int rrfK,
        int hitCount,
        long costMs,
        String status,
        String errorMsg,
        LocalDateTime createdAt
) {
    public boolean finished() {
        return "SUCCESS".equals(status) || "FAILED".equals(status);
    }
}
