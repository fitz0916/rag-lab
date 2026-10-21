package com.raglab.eval.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一条测试用例（阶段六）。
 *
 * expect 是「期望关键词组」：
 *   [["采集深度","E.12"], ["采集深度","Ⅱ级"]]
 * 含义：一段结果只要**完整包含任意一组**里的全部关键词，就算「相关」。
 *
 * 为什么不用「标注正确的 chunk_id」？
 *   因为你随时会改 chunk size 重新入库，chunk_id 全变了，标注就作废了。
 *   关键词组跟内容走，不跟切片编号走 —— 改参数重跑，测试集不用重写。
 *
 * 代价也很明确：关键词只能验证「字面命中」，验证不了「语义正确」。
 * 这是 RAG 评测的常态 —— 真正的语义评估得靠大模型打分或人工标注。
 */
public record EvalCase(
        long id,
        String name,
        String query,
        List<List<String>> expect,
        String difficulty,
        boolean enabled,
        LocalDateTime createdAt
) {

    public int groupCount() {
        return expect == null ? 0 : expect.size();
    }

    public static String formatGroups(List<List<String>> groups) {
        if (groups == null || groups.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < groups.size(); i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(String.join("+", groups.get(i)));
        }
        return sb.toString();
    }
}
