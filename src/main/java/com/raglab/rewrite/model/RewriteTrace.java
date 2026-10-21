package com.raglab.rewrite.model;

import java.time.LocalDateTime;

/** 一次查询重写的轨迹（阶段八） */
public record RewriteTrace(
        String id,
        String queryText,
        int variantCount,
        int topK,
        int recallTopN,
        boolean rerank,
        long costMs,
        int promptTokens,
        String status,
        String errorMsg,
        LocalDateTime createdAt
) {
    public boolean finished() {
        return "SUCCESS".equals(status) || "FAILED".equals(status);
    }
}
