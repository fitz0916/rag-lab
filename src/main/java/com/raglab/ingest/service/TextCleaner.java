package com.raglab.ingest.service;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 文本清洗。目的是让切片和向量化拿到「干净、稳定」的输入。
 *
 * 干四件具体的事：
 * 1. 统一换行符（Windows 的 \r\n 会让切片长度计算全错）
 * 2. 删掉零宽字符 / BOM（从网页复制的内容常带，肉眼看不见但会污染向量）
 * 3. 折叠连续空行（PDF 解析出来经常有 5、6 个连续空行）
 * 4. 去掉控制字符（\u0000 之类，Chroma / JSON 序列化会直接报错）
 */
@Component
public class TextCleaner {

    /** 零宽空格、零宽连接符、BOM、方向标记 */
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200F\\uFEFF\\u00AD]");
    /** 除 \n \t 以外的控制字符 */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");
    private static final Pattern TRAILING_SPACE = Pattern.compile("[ \\t]+\\n");
    private static final Pattern MULTI_BLANK = Pattern.compile("\\n{3,}");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t]{2,}");

    public CleanResult clean(String raw) {
        if (raw == null) {
            return new CleanResult("", 0, 0, 0);
        }
        int before = raw.length();
        int linesBefore = countLines(raw);

        String s = raw.replace("\r\n", "\n").replace('\r', '\n');
        s = ZERO_WIDTH.matcher(s).replaceAll("");
        s = CONTROL.matcher(s).replaceAll("");
        s = TRAILING_SPACE.matcher(s).replaceAll("\n");
        s = MULTI_SPACE.matcher(s).replaceAll(" ");
        s = MULTI_BLANK.matcher(s).replaceAll("\n\n");
        s = s.strip();

        int after = s.length();
        int linesAfter = countLines(s);
        return new CleanResult(s, before, after, Math.max(0, linesBefore - linesAfter));
    }

    private int countLines(String s) {
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }
}
