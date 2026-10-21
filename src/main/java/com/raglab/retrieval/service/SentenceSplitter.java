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
 */
@Component
public class SentenceSplitter {

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？；!?;])");
    private static final Pattern SEPARATOR_LINE = Pattern.compile("^[-=*_]{3,}$");
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
        StringBuilder carry = new StringBuilder();   // 过短句子的累积缓冲

        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.strip();

            // ---- 代码块围栏 ----
            if (line.startsWith("```")) {
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
                flushCarry(carry, out);
                continue;
            }
            // ---- 表格行 / 标题行：整体作为一句 ----
            if (line.startsWith("|") || line.startsWith("#")) {
                flushCarry(carry, out);
                addIfValid(line, out);
                continue;
            }
            // ---- 分隔线：丢掉 ----
            if (SEPARATOR_LINE.matcher(line).matches()) {
                flushCarry(carry, out);
                continue;
            }

            // ---- 普通文本：按句末标点切 ----
            for (String piece : SENTENCE_END.split(line)) {
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

        if (inCode && codeBuf.length() > 0) {
            addIfValid(codeBuf.toString().strip(), out);
        }
        flushCarry(carry, out);
        return out;
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
