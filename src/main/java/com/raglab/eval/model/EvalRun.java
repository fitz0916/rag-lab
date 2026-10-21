package com.raglab.eval.model;

import java.time.LocalDateTime;

/** 一次评测运行（阶段六） */
public record EvalRun(
        String id,
        String strategies,
        int topK,
        int recallTopN,
        int caseCount,
        String status,
        long costMs,
        String errorMsg,
        LocalDateTime createdAt
) {
    public boolean finished() {
        return "SUCCESS".equals(status) || "FAILED".equals(status);
    }
}
