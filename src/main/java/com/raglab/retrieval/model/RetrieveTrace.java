package com.raglab.retrieval.model;

import java.time.LocalDateTime;

/** 一次检索的轨迹。id 同时也是 SSE 日志的 task_id。 */
public record RetrieveTrace(
        String id,
        String queryText,
        int topK,
        int hitCount,
        Double top1Score,
        Integer queryDim,
        Double queryNorm,
        String queryHead,
        Integer chromaCount,
        long costMs,
        String status,
        String errorMsg,
        LocalDateTime createdAt
) {
    public boolean finished() {
        return "SUCCESS".equals(status) || "FAILED".equals(status);
    }
}
