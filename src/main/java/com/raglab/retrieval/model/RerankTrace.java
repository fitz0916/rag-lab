package com.raglab.retrieval.model;

import java.time.LocalDateTime;

/** 一次重排的轨迹 */
public record RerankTrace(
        String id,
        String queryText,
        int recallTopn,
        int finalTopk,
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
