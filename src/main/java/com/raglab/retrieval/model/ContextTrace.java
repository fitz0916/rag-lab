package com.raglab.retrieval.model;

import java.time.LocalDateTime;

/** 一次上下文压缩的轨迹 */
public record ContextTrace(
        String id,
        String queryText,
        int recallTopk,
        String strategy,
        int maxChars,
        int originChars,
        int finalChars,
        int sentenceCount,
        Double compressRatio,
        long costMs,
        String status,
        String errorMsg,
        LocalDateTime createdAt
) {
    public boolean finished() {
        return "SUCCESS".equals(status) || "FAILED".equals(status);
    }
}
