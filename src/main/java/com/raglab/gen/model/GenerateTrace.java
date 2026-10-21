package com.raglab.gen.model;

import java.time.LocalDateTime;

/** 一次生成的轨迹（阶段七） */
public record GenerateTrace(
        String id,
        String queryText,
        String model,
        int sourceChunks,
        int sentenceCount,
        int contextChars,
        int promptChars,
        int answerChars,
        int promptTokens,
        int completionTokens,
        int citedCount,
        long costMs,
        String status,
        String errorMsg,
        LocalDateTime createdAt
) {
    public boolean finished() {
        return "SUCCESS".equals(status) || "FAILED".equals(status);
    }
}
