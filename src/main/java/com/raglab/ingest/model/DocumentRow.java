package com.raglab.ingest.model;

import java.time.LocalDateTime;

/** 文档行快照，给前端做列表展示 */
public record DocumentRow(
        long id,
        String docName,
        String sourceType,
        String contentHash,
        int charCount,
        int chunkCount,
        int vectorCount,
        String status,
        String errorMsg,
        String collection,
        LocalDateTime createdAt
) {
}
