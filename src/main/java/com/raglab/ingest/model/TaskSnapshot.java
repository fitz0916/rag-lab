package com.raglab.ingest.model;

import java.time.LocalDateTime;

/** 任务快照，前端轮询/刷新后恢复状态用 */
public record TaskSnapshot(
        String id,
        Long docId,
        String docName,
        String status,
        String currentStage,
        int totalChunks,
        int doneChunks,
        int storedVectors,
        String errorMsg,
        String errorStage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        long costMs
) {
    public boolean finished() {
        return "SUCCESS".equals(status) || "FAILED".equals(status);
    }
}
