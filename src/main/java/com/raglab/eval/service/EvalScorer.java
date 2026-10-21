package com.raglab.eval.service;

import com.raglab.eval.model.EvalScore;
import com.raglab.eval.model.EvalUnit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 指标计算（阶段六）。
 *
 * 判定「相关」的规则只有一条，必须写死在代码里、并在页面上说清楚：
 *   结果文本（小写、空白归一后）**完整包含**某个期望组的**全部关键词** → 这条结果对这个组算相关。
 *   一个用例有多个组，命中任意一组就算这条结果相关；coverage 才是「命中了几个组」。
 *
 * 两个容易做错的地方：
 *   1. 只在 Top-K 内算指标。跑到第 20 名才命中的，不能算 Hit@5。
 *   2. MRR 只取**第一条**相关结果的位置。后面又命中几条也不加分 —— 因为它问的是
 *      「第一条对的排多靠前」，不是「一共能捞到几条」。
 */
@Component
public class EvalScorer {

    public EvalScore score(List<EvalUnit> units, List<List<String>> expect, int topK) {
        if (units == null || units.isEmpty() || expect == null || expect.isEmpty()) {
            return EvalScore.miss();
        }
        List<String> matched = new ArrayList<>();
        int firstRank = -1;

        int limit = Math.min(units.size(), Math.max(1, topK));
        for (int i = 0; i < limit; i++) {
            EvalUnit u = units.get(i);
            String text = normalize(u.text());
            for (List<String> group : expect) {
                if (group == null || group.isEmpty()) {
                    continue;
                }
                String label = String.join("+", group);
                if (matched.contains(label)) {
                    continue;
                }
                if (matchesAll(text, group)) {
                    matched.add(label);
                    if (firstRank < 0) {
                        firstRank = u.rank();
                    }
                }
            }
        }

        double coverage = (double) matched.size() / expect.size();
        double mrr = firstRank > 0 ? 1.0 / firstRank : 0;
        return new EvalScore(firstRank == 1, firstRank >= 1 && firstRank <= 3,
                firstRank > 0, firstRank, round4(mrr), round4(coverage), matched);
    }

    /**
     * 文本是否包含这一组的全部关键词。
     * 关键词本身也做同样的归一化，避免「k = 60」和「k  =  60」这种差异导致漏判。
     */
    public boolean matchesAll(String normalizedText, List<String> group) {
        for (String kw : group) {
            if (kw == null || kw.isBlank()) {
                continue;
            }
            if (!normalizedText.contains(normalize(kw))) {
                return false;
            }
        }
        return true;
    }

    /** 小写 + 空白折叠：换行、多个空格统一成一个空格，避免跨行短语匹配不上 */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static double round4(double d) {
        return Math.round(d * 10000.0) / 10000.0;
    }
}
