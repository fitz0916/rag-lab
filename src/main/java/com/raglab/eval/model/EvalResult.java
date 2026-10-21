package com.raglab.eval.model;

import java.util.List;

/**
 * 评测的完整结果。前端基本就渲染这一份数据。
 *
 * 两层：
 *   strategies —— 按策略聚合的横向对比（谁的命中率高、谁更慢）
 *   cases      —— 逐用例的明细（谁在哪个问题上翻车）
 *
 * 只有第一层会骗人：某个策略平均 Hit@1 高一点，但可能是靠简单题撑起来的。
 * 逐用例看才能发现「它在硬题上全错」。
 */
public record EvalResult(
        int caseCount,
        int topK,
        int recallTopN,
        List<StrategySummary> strategies,
        List<CaseRow> cases
) {

    public record StrategySummary(
            String strategy,
            int cases,
            int failures,
            int hit1,
            int hit3,
            int hitK,
            double hit1Rate,
            double hit3Rate,
            double hitKRate,
            double mrr,
            double avgCoverage,
            double avgLatencyMs,
            long totalLatencyMs,
            // 只有 CONTEXT 有值：压缩前字数 / 压缩后字数 / 压缩后上下文里的期望组覆盖率
            double avgSourceChars,
            double avgContextChars,
            double avgContextCoverage
    ) {
    }

    public record CaseRow(
            long caseId,
            String name,
            String query,
            String difficulty,
            int groupCount,
            List<CaseMetric> metrics
    ) {
    }

    public record CaseMetric(
            String strategy,
            boolean hit1,
            boolean hit3,
            boolean hitK,
            int firstRank,
            double mrr,
            double coverage,
            long latencyMs,
            List<String> matched,
            String error,
            // 压缩阶段专用：源切片总字数 / 上下文总字数 / 上下文里期望组的覆盖率
            int sourceChars,
            int contextChars,
            double contextCoverage,
            List<EvalUnit> top
    ) {
        public boolean compressed() {
            return contextChars > 0;
        }

        public double compressRatio() {
            return sourceChars <= 0 ? 0 : Math.round((double) contextChars / sourceChars * 1000) / 1000.0;
        }
    }
}
