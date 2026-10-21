package com.raglab.retrieval.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 上下文组装（阶段五收尾）。
 *
 * 三件事，顺序不能反：
 *   1. 去重 —— 阶段一的 overlap 会让相邻切片有重复句子，不去重等于白压缩
 *   2. 按分数选 —— 高分优先，但要限制「每个切片最多贡献几勺」，防止某一片霸占名额
 *   3. 按原文顺序排 —— 最后一定要重排回原文顺序，否则句子跳来跳去读不通
 */
@Component
public class ContextBuilder {

    public record Config(int maxSentencesPerChunk, int maxChars, double dedupThreshold) {
    }

    public record Outcome(List<SentenceExtractor.ScoredSentence> selected,
                          int droppedDuplicates, int droppedByLimit) {
    }

    /**
     * @param scored 已打分的全部句子（未排序）
     */
    public Outcome build(List<SentenceExtractor.ScoredSentence> scored, Config cfg) {
        if (scored.isEmpty()) {
            return new Outcome(List.of(), 0, 0);
        }

        // 1. 按分数降序；分数相同按切片顺序（保证结果稳定可复现）
        List<SentenceExtractor.ScoredSentence> sorted = new ArrayList<>(scored);
        sorted.sort(Comparator
                .comparingDouble(SentenceExtractor.ScoredSentence::score).reversed()
                .thenComparingInt(SentenceExtractor.ScoredSentence::sourceRank)
                .thenComparingInt(SentenceExtractor.ScoredSentence::orderInChunk));

        List<SentenceExtractor.ScoredSentence> picked = new ArrayList<>();
        Map<String, Integer> perChunk = new HashMap<>();
        int chars = 0;
        int droppedDuplicates = 0;
        int droppedByLimit = 0;

        for (SentenceExtractor.ScoredSentence s : sorted) {
            int used = perChunk.getOrDefault(s.chromaId(), 0);
            if (used >= cfg.maxSentencesPerChunk()) {
                continue;
            }
            // 2. 去重：和已选句子太像就跳过
            boolean dup = false;
            for (SentenceExtractor.ScoredSentence p : picked) {
                if (similarity(p.text(), s.text()) >= cfg.dedupThreshold()) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                droppedDuplicates++;
                continue;
            }
            // 3. 长度上限
            if (chars + s.text().length() > cfg.maxChars()) {
                droppedByLimit++;
                continue;
            }
            picked.add(s);
            perChunk.merge(s.chromaId(), 1, Integer::sum);
            chars += s.text().length();
        }

        // 4. 按原文顺序重排（文档 → 切片 → 片内序号），读起来才连贯
        picked.sort(Comparator
                .comparingLong(SentenceExtractor.ScoredSentence::docId)
                .thenComparingInt(SentenceExtractor.ScoredSentence::chunkIndex)
                .thenComparingInt(SentenceExtractor.ScoredSentence::orderInChunk));

        return new Outcome(picked, droppedDuplicates, droppedByLimit);
    }

    /**
     * 字符 2-gram Jaccard 相似度。
     *
     * 为什么不用向量？因为去重要对每一对已选句子算，O(n²) 次；
     * 而这个场景的重复基本都是 overlap 造成的**字面重复**，Jaccard 又快又准。
     */
    double similarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        Set<String> sa = bigrams(a);
        Set<String> sb = bigrams(b);
        if (sa.isEmpty() || sb.isEmpty()) {
            return 0;
        }
        int inter = 0;
        for (String s : sa) {
            if (sb.contains(s)) {
                inter++;
            }
        }
        int union = sa.size() + sb.size() - inter;
        return union == 0 ? 0 : (double) inter / union;
    }

    private Set<String> bigrams(String s) {
        Set<String> set = new HashSet<>();
        String t = s.replaceAll("\\s+", "");
        for (int i = 0; i + 1 < t.length(); i++) {
            set.add(t.substring(i, i + 2));
        }
        if (t.length() == 1) {
            set.add(t);
        }
        return set;
    }
}
