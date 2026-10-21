package com.raglab.ingest.model;

import java.time.LocalDateTime;

/**
 * 一条「运行记录」= 一次入库 / 一次向量检索 / 一次混合检索。
 *
 * 三类任务原本散在三张表里，这里统一成一个视图给前端，
 * 用户不用记 taskId 也能翻历史。
 *
 * @param logCount   该次运行产生了多少条步骤日志（列表里展示，不用点进去）
 * @param errorCount 其中几条是 ERROR
 */
public record RunRecord(
        String type,
        String id,
        String title,
        String status,
        long costMs,
        LocalDateTime ts,
        String summary,
        String errorMsg,
        int logCount,
        int errorCount
) {
    public static final String TYPE_INGEST = "INGEST";
    public static final String TYPE_RETRIEVE = "RETRIEVE";
    public static final String TYPE_HYBRID = "HYBRID";
}
