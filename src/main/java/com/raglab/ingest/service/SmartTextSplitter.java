package com.raglab.ingest.service;

import com.raglab.config.RagLabProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能切片器 —— 阶段一最核心的一步。
 *
 * 为什么要分层切，而不是简单按字数切？
 * 按固定字数硬切会把一句话、一张表格、一段代码劈成两半，向量化之后语义就废了。
 * 文章里说「每个片段控制在 200-500 字」，但更重要的是「切在语义边界上」。
 *
 * 所以这里的顺序是：
 *   Markdown 标题 → 段落 → 句子 → 实在超长才硬切
 * 并且相邻切片保留 overlap 字符，避免答案正好被切断在边界上。
 */
@Component
public class SmartTextSplitter {

    /** 一个切片 */
    public record RawChunk(int index, String heading, String content) {
    }

    /** 切片统计，直接打到日志里，方便你判断参数调得对不对 */
    public record SplitStats(int total, int avgChars, int minChars, int maxChars, int mergedTail, int hardCut) {
    }

    public record Outcome(List<RawChunk> chunks, SplitStats stats) {
    }

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？!?；;])");
    /** 硬切时优先在这些位置断开 */
    private static final char[] SOFT_BREAKS = {'，', ',', '、', ' ', '：', ':'};

    private final RagLabProperties props;

    public SmartTextSplitter(RagLabProperties props) {
        this.props = props;
    }

    public Outcome split(String text) {
        return split(text, null, null, null);
    }

    /**
     * @param sizeOverride    为 null 时用 application.yml 的配置
     * @param overlapOverride 同上
     * @param minSizeOverride 同上
     */
    public Outcome split(String text, Integer sizeOverride, Integer overlapOverride, Integer minSizeOverride) {
        int size = sizeOverride != null && sizeOverride > 0 ? sizeOverride : props.chunk().size();
        int rawOverlap = overlapOverride != null && overlapOverride >= 0 ? overlapOverride : props.chunk().overlap();
        int minSize = minSizeOverride != null && minSizeOverride >= 0 ? minSizeOverride : props.chunk().minSize();
        if (rawOverlap >= size) {
            throw new IllegalArgumentException("overlap(" + rawOverlap + ") 必须小于 size(" + size + ")，否则切片会无限重叠");
        }
        int overlap = Math.min(rawOverlap, size / 2);
        int core = Math.max(50, size - overlap);

        List<Section> sections = splitSections(text);

        List<CoreChunk> cores = new ArrayList<>();
        int hardCutCount = 0;
        for (Section section : sections) {
            for (String paragraph : section.body().split("\n{2,}")) {
                String p = paragraph.strip();
                if (p.isEmpty()) {
                    continue;
                }
                for (String unit : splitToUnits(p, core)) {
                    if (unit.length() > core) {
                        hardCutCount++;
                    }
                    cores.add(new CoreChunk(section.heading(), unit));
                }
            }
        }

        // 贪心合并相邻单元，直到接近 core 长度
        List<CoreChunk> merged = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        String bufferHeading = null;
        for (CoreChunk c : cores) {
            if (buffer.length() == 0) {
                buffer.append(c.text());
                bufferHeading = c.heading();
            } else if (buffer.length() + 2 + c.text().length() <= core) {
                buffer.append("\n\n").append(c.text());
            } else {
                merged.add(new CoreChunk(bufferHeading, buffer.toString()));
                buffer.setLength(0);
                buffer.append(c.text());
                bufferHeading = c.heading();
            }
        }
        if (buffer.length() > 0) {
            merged.add(new CoreChunk(bufferHeading, buffer.toString()));
        }

        // 尾块过短就并进前一块（避免出现「只有 10 个字」的碎片切片）
        int mergedTail = 0;
        if (merged.size() > 1) {
            CoreChunk last = merged.get(merged.size() - 1);
            if (last.text().length() < minSize) {
                CoreChunk prev = merged.get(merged.size() - 2);
                merged.set(merged.size() - 2, new CoreChunk(prev.heading(), prev.text() + "\n\n" + last.text()));
                merged.remove(merged.size() - 1);
                mergedTail = 1;
            }
        }

        // 加 overlap 前缀，生成最终切片
        List<RawChunk> chunks = new ArrayList<>(merged.size());
        String tail = null;
        for (int i = 0; i < merged.size(); i++) {
            CoreChunk c = merged.get(i);
            String content = (tail == null || tail.isEmpty()) ? c.text() : tail + c.text();
            chunks.add(new RawChunk(i, c.heading(), content));
            tail = overlap > 0 ? tailOf(c.text(), overlap) : "";
        }

        int total = chunks.size();
        int sum = chunks.stream().mapToInt(c -> c.content().length()).sum();
        int min = chunks.stream().mapToInt(c -> c.content().length()).min().orElse(0);
        int max = chunks.stream().mapToInt(c -> c.content().length()).max().orElse(0);
        return new Outcome(chunks, new SplitStats(total, total == 0 ? 0 : sum / total, min, max, mergedTail, hardCutCount));
    }

    // ------------------------------------------------------------------

    private record Section(String heading, String body) {
    }

    private record CoreChunk(String heading, String text) {
    }

    /** 按 Markdown 标题切 section，heading 会一路继承到切片上 */
    private List<Section> splitSections(String text) {
        List<Section> sections = new ArrayList<>();
        String currentHeading = null;
        StringBuilder body = new StringBuilder();

        for (String line : text.split("\n", -1)) {
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                if (!body.toString().isBlank()) {
                    sections.add(new Section(currentHeading, body.toString().strip()));
                }
                body.setLength(0);
                // 标题层级用符号前缀保留，检索结果里能看出层级关系
                currentHeading = m.group(1) + " " + m.group(2);
            } else {
                body.append(line).append('\n');
            }
        }
        if (!body.toString().isBlank()) {
            sections.add(new Section(currentHeading, body.toString().strip()));
        }
        return sections;
    }

    /** 段落 → 句子 → 硬切，保证返回的每个单元都不超过 core */
    private List<String> splitToUnits(String paragraph, int core) {
        if (paragraph.length() <= core) {
            return List.of(paragraph);
        }
        List<String> sentences = new ArrayList<>();
        for (String s : SENTENCE_END.split(paragraph)) {
            if (!s.isBlank()) {
                sentences.add(s.strip());
            }
        }
        if (sentences.isEmpty()) {
            sentences.add(paragraph);
        }

        List<String> units = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String s : sentences) {
            if (s.length() > core) {
                if (cur.length() > 0) {
                    units.add(cur.toString());
                    cur.setLength(0);
                }
                units.addAll(hardCut(s, core));
                continue;
            }
            if (cur.length() == 0) {
                cur.append(s);
            } else if (cur.length() + s.length() <= core) {
                cur.append(s);
            } else {
                units.add(cur.toString());
                cur.setLength(0);
                cur.append(s);
            }
        }
        if (cur.length() > 0) {
            units.add(cur.toString());
        }
        return units;
    }

    /** 一句话就超过切片长度时，只能在标点处硬切 */
    private List<String> hardCut(String sentence, int core) {
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < sentence.length()) {
            int end = Math.min(start + core, sentence.length());
            if (end < sentence.length()) {
                // 在 core 的最后 30% 区间里往回找标点，找到就在那儿断
                int floor = start + (int) (core * 0.7);
                for (int probe = end; probe > floor; probe--) {
                    if (isSoftBreak(sentence.charAt(probe - 1))) {
                        end = probe;
                        break;
                    }
                }
            }
            String piece = sentence.substring(start, end).strip();
            if (!piece.isEmpty()) {
                out.add(piece);
            }
            start = end;
        }
        return out;
    }

    private boolean isSoftBreak(char c) {
        for (char b : SOFT_BREAKS) {
            if (c == b) {
                return true;
            }
        }
        return false;
    }

    /** 取尾部 overlap 个字符，尽量从标点后开始，读起来不会断在半句话上 */
    private String tailOf(String text, int overlap) {
        if (text.length() <= overlap) {
            return text;
        }
        int start = text.length() - overlap;
        for (int i = start; i < Math.min(text.length(), start + overlap / 2); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n') {
                return text.substring(i + 1);
            }
        }
        return text.substring(start);
    }
}
