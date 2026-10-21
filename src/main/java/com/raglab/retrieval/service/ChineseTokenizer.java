package com.raglab.retrieval.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 轻量中文分词器（BM25 用）。
 *
 * 为什么不用 jieba / HanLP？两个原因：
 * 1. 这个类只需要 30 行，而词典分词器要拖几十 MB 依赖
 * 2. BM25 对分词精度的要求其实不高 —— 它靠 IDF 自己压低高频词的权重
 *
 * 切分规则（关键在第一条）：
 *   - 字母/数字串（含 . _ - /）**整体保留** →  "3.10" 是 token，"match-case" 是 token
 *   - 中文串切 **bigram**（相邻两字）→ "大模型的幻觉" → 大模, 模型, 型的, 的幻, 幻觉
 *   - 单个汉字单独成 token
 *   - 全部小写
 *
 * 第一条是重点：向量检索分不清 3.10 和 3.11，BM25 能分清，前提就是别把 "3.10" 切成 "3" 和 "10"。
 * Lucene 的 CJKAnalyzer 对中文也是 bigram，效果够用。
 */
@Component
public class ChineseTokenizer {

    /** 极简停用词：只去掉对 BM25 毫无区分度的虚词 */
    private static final Set<String> STOPWORDS = Set.of(
            "的", "了", "是", "在", "和", "与", "就", "也", "都", "而", "及", "或",
            "这", "那", "有", "不", "很", "会", "把", "被", "为", "以", "对", "从"
    );

    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();

        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isAsciiWordChar(c)) {
                flushCjk(cjk, tokens);
                ascii.append(c);
            } else if (isCjk(c)) {
                flushAscii(ascii, tokens);
                cjk.append(c);
            } else {
                flushAscii(ascii, tokens);
                flushCjk(cjk, tokens);
            }
        }
        flushAscii(ascii, tokens);
        flushCjk(cjk, tokens);

        return tokens.stream().filter(t -> !STOPWORDS.contains(t)).toList();
    }

    /** 便于日志展示：分词结果 + 去重后的词元 */
    public List<String> distinctTerms(String text) {
        return tokenize(text).stream().distinct().toList();
    }

    private void flushAscii(StringBuilder sb, List<String> out) {
        if (sb.length() == 0) {
            return;
        }
        String s = sb.toString();
        sb.setLength(0);
        // 去掉首尾的 . _ - / （句末标点会被吸进来）
        int start = 0;
        int end = s.length();
        while (start < end && isAsciiPunct(s.charAt(start))) {
            start++;
        }
        while (end > start && isAsciiPunct(s.charAt(end - 1))) {
            end--;
        }
        if (start < end) {
            out.add(s.substring(start, end));
        }
    }

    private void flushCjk(StringBuilder sb, List<String> out) {
        if (sb.length() == 0) {
            return;
        }
        String s = sb.toString();
        sb.setLength(0);
        if (s.length() == 1) {
            out.add(s);
            return;
        }
        for (int i = 0; i + 1 < s.length(); i++) {
            out.add(s.substring(i, i + 2));
        }
    }

    private boolean isAsciiWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '.' || c == '_' || c == '-' || c == '/';
    }

    private boolean isAsciiPunct(char c) {
        return c == '.' || c == '_' || c == '-' || c == '/';
    }

    private boolean isCjk(char c) {
        return (c >= '\u4e00' && c <= '\u9fff')      // 基本汉字
                || (c >= '\u3400' && c <= '\u4dbf'); // 扩展 A
    }
}
