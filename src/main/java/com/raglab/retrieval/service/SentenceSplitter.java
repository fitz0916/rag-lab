package com.raglab.retrieval.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 句子切分（阶段五的地基）。
 *
 * 为什么要拆到句子？因为「切片」是为了检索，粒度约 500 字；但给大模型的上下文不需要这么多 ——
 * 一片里可能只有一句真正回答问题，剩下 400 多字都是噪音。拆到句子才能精准挑。
 *
 * 几个特殊处理（不做就等着出乱子）：
 *   - 代码块（```）整体保留，拆开会变成一堆没意义的碎片
 *   - 表格行（|）整体保留，拆开就没法读了
 *   - 标题行（#）保留，它本身是上下文提示
 *   - 过短的碎片（< 8 字）并入下一句，避免出现「是的。」这种独立句子
 *
 * ★ 最容易踩的坑：**换行不等于句末**。
 *   PDF 和 Word 导出的文本，常在句子中间硬折行，比如：
 *       ……其模型的采集深度应执行表
 *       E.12 和表 E.1 ～表 E.11 的第Ⅱ级要求。
 *   如果按行切，就会得到「……应执行表」和「E.12 和表……」两个残句 ——
 *   残句喂给大模型，答案就会断章取义（实测生成过一次「第Ⅰ级」，因为关键半句被切走了）。
 *   所以这里改成：连续的普通行先累积，只有遇到句末标点 / 空行 / 标题 / 表格 / 列表项才断开。
 */
@Component
public class SentenceSplitter {

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？；!?;])");
    private static final Pattern SEPARATOR_LINE = Pattern.compile("^[-=*_]{3,}$");
    /** 列表项：- / * / • / 1. / 1、/ (1) */
    private static final Pattern BULLET = Pattern.compile("^([-*•]|\\d{1,2}[.、)])\\s+.*");
    private static final int MIN_LEN = 8;

    /**
     * @return 按原文顺序排列的句子列表
     */
    public List<String> split(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }

        boolean inCode = false;
        StringBuilder codeBuf = new StringBuilder();
        StringBuilder para = new StringBuilder();   // 跨行累积的普通文本
        StringBuilder carry = new StringBuilder();  // 过短句子的累积缓冲

        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.strip();

            // ---- 代码块围栏 ----
            if (line.startsWith("```")) {
                flushPara(para, carry, out);
                if (inCode) {
                    addIfValid(codeBuf.toString().strip(), out);
                    codeBuf.setLength(0);
                    inCode = false;
                } else {
                    flushCarry(carry, out);
                    inCode = true;
                }
                continue;
            }
            if (inCode) {
                codeBuf.append(rawLine).append('\n');
                continue;
            }

            if (line.isEmpty()) {
                flushPara(para, carry, out);
                continue;
            }
            // ---- 表格行 / 标题行：整体作为一句 ----
            if (line.startsWith("|") || line.startsWith("#")) {
                flushPara(para, carry, out);
                flushCarry(carry, out);
                addIfValid(line, out);
                continue;
            }
            // ---- 分隔线：丢掉 ----
            if (SEPARATOR_LINE.matcher(line).matches()) {
                flushPara(para, carry, out);
                continue;
            }
            // ---- 列表项：各自独立，不和上一段粘连 ----
            if (BULLET.matcher(line).matches()) {
                flushPara(para, carry, out);
                appendLine(para, line);
                flushPara(para, carry, out);
                continue;
            }

            // ---- 普通文本：先累积，句末标点才断 ----
            appendLine(para, line);
            if (endsWithSentenceEnd(line)) {
                flushPara(para, carry, out);
            }
        }

        flushPara(para, carry, out);
        if (inCode && codeBuf.length() > 0) {
            addIfValid(codeBuf.toString().strip(), out);
        }
        flushCarry(carry, out);
        return out;
    }

    /** 跨行拼接：中文直接接上，英文/数字之间补一个空格，避免把两个词粘成一个 */
    private void appendLine(StringBuilder para, String line) {
        if (para.length() > 0) {
            char prev = para.charAt(para.length() - 1);
            char next = line.charAt(0);
            boolean asciiPair = isAsciiWordChar(prev) && isAsciiWordChar(next);
            if (asciiPair) {
                para.append(' ');
            }
        }
        para.append(line);
    }

    private boolean isAsciiWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private boolean endsWithSentenceEnd(String line) {
        if (line.isEmpty()) {
            return false;
        }
        char c = line.charAt(line.length() - 1);
        return "。！？；!?;".indexOf(c) >= 0;
    }

    /** 把累积的段落按句末标点切开，再交给「短句合并」逻辑 */
    private void flushPara(StringBuilder para, StringBuilder carry, List<String> out) {
        if (para.length() == 0) {
            return;
        }
        String text = para.toString().strip();
        para.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        for (String piece : SENTENCE_END.split(text)) {
            String t = piece.strip();
            if (t.isEmpty()) {
                continue;
            }
            String merged = carry.length() > 0 ? carry.toString() + t : t;
            if (merged.length() < MIN_LEN) {
                carry.setLength(0);
                carry.append(merged);
            } else {
                addIfValid(merged, out);
                carry.setLength(0);
            }
        }
    }

    private void flushCarry(StringBuilder carry, List<String> out) {
        if (carry.length() > 0) {
            addIfValid(carry.toString().strip(), out);
            carry.setLength(0);
        }
    }

    private void addIfValid(String s, List<String> out) {
        if (s == null) {
            return;
        }
        String t = s.strip();
        // 纯符号 / 纯空白不要
        if (t.isEmpty() || t.replaceAll("[\\p{P}\\s]", "").isEmpty()) {
            return;
        }
        out.add(t);
    }
}
