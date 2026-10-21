package com.raglab.eval.model;

import java.util.List;

/**
 * 一个「用例 × 策略」的打分结果。
 *
 * 指标含义（都以 Top-K 为界）：
 *   firstRank  第一条相关结果排第几，-1 = Top-K 里一条都没有
 *   hit1       Top1 就是相关 —— 最严格，直接决定大模型看到的重点
 *   hit3       Top3 里有相关 —— 通常上下文会给 3 条，这个更贴近实际
 *   hitK       TopK 里有相关 —— 有救
 *   mrr        1/firstRank 的平均（单个用例就是 1/firstRank），衡量「排得够不够靠前」
 *   coverage   Top-K 里命中的期望关键词组数 / 总组数 —— 衡量「答案是否完整」
 *
 * 为什么 hit 之外还要 coverage：
 *   一个问题可能要两个条件才答得全（比如「采集深度」+「执行表 E.12」）。
 *   Top1 只命中一个条件也算 hit@1，但答案其实不完整 —— coverage 能暴露这种情况。
 */
public record EvalScore(
        boolean hit1,
        boolean hit3,
        boolean hitK,
        int firstRank,
        double mrr,
        double coverage,
        List<String> matched
) {
    public static EvalScore miss() {
        return new EvalScore(false, false, false, -1, 0, 0, List.of());
    }
}
