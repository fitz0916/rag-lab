package com.raglab.rewrite.service;

import com.raglab.config.RagLabProperties;
import com.raglab.gen.service.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 查询改写（阶段八 / 对应文章「三、优化1」）。
 *
 * 解决的是「用词不匹配」：用户说「大模型瞎编怎么办」，文档里写的是「幻觉」「准确性」。
 * 向量检索对这种同义词其实有一定容忍度，但遇到口语、缩写、业务黑话就明显吃亏；
 * 关键词检索（BM25）更是完全匹配不上。
 *
 * 做法很朴素 —— 让大模型把同一个问题换几种说法：
 *   原问题：大模型瞎编怎么办
 *   变体1：大模型的幻觉问题如何缓解
 *   变体2：LLM 生成错误信息的准确性改进
 *
 * 注意这不是「向量相似度」做的，是纯文本生成（文章里特意强调过）。
 * 所以 temperature 要比问答高（0.7），否则每次改写都差不多，白花钱。
 *
 * 三个必须守住的边界：
 *   1. **原查询永远保留**，改写只是补充。改写跑偏时，至少还有原来那条兜底。
 *   2. **保留数字、版本号、专有名词**。把「3.10」改写成「3.x」这种就彻底废了。
 *   3. **解析要宽容、去重要严格**。模型经常多输出编号、引号、解释，都得洗掉；
 *      洗完和原问题一样的，直接丢弃 —— 它不会带来任何新召回，只会稀释融合权重。
 */
@Component
public class QueryRewriter {

    /** 单条变体的长度上限，太长说明模型在解释而不是改写 */
    private static final int MAX_VARIANT_CHARS = 200;
    /** 多输出的行数上限，防止模型啰嗦一大堆 */
    private static final int MAX_LINES_TO_PARSE = 12;

    private final ChatClient chatClient;
    private final RagLabProperties props;

    public QueryRewriter(ChatClient chatClient, RagLabProperties props) {
        this.chatClient = chatClient;
        this.props = props;
    }

    public record Outcome(List<String> variants, int promptTokens, int completionTokens,
                          long costMs, String raw) {
    }

    public Outcome rewrite(String query, int variants) {
        int n = Math.max(1, Math.min(variants, 5));
        long t0 = System.currentTimeMillis();
        ChatClient.Result r = chatClient.chat(systemPrompt(n), userPrompt(query),
                props.rewrite().temperature());
        List<String> parsed = parse(r.content(), query, n);
        return new Outcome(parsed, r.usage().promptTokens(), r.usage().completionTokens(),
                System.currentTimeMillis() - t0, r.content());
    }

    private String systemPrompt(int n) {
        return """
                你是检索系统的查询改写器。
                任务：把用户的问题改写成 %d 个「表达不同、意思相同」的检索查询。
                规则：
                1. 每行一个，不要编号、不要解释、不要空行。
                2. 必须保留原问题里的数字、版本号、专有名词，不许换成别的概念。
                3. 可以补充同义说法和上位词，例如「瞎编」→「幻觉」，「怎么弄」→「如何实现」。
                4. 写成搜索式的短句或词组，不要写成解释性长句。
                5. 只输出改写结果，不要重复原问题。
                """.formatted(n).strip();
    }

    private String userPrompt(String query) {
        return "原问题：" + query;
    }

    /**
     * 解析模型输出。模型很爱多输出这些东西，全都要洗掉：
     *   "1. 大模型的幻觉问题"      → 编号
     *   "- 大模型的幻觉问题"        → 列表符号
     *   "「大模型的幻觉问题」"      → 引号
     *   "改写1：大模型的幻觉问题"   → 前缀说明
     */
    List<String> parse(String raw, String original, int limit) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        seen.add(normalize(original));

        String[] lines = raw.split("\\r?\\n");
        int considered = 0;
        for (String line : lines) {
            if (considered >= MAX_LINES_TO_PARSE || out.size() >= limit) {
                break;
            }
            String t = clean(line);
            if (t.isEmpty()) {
                continue;
            }
            considered++;
            if (t.length() > MAX_VARIANT_CHARS) {
                continue;
            }
            String norm = normalize(t);
            if (norm.isEmpty() || !seen.add(norm)) {
                continue;
            }
            out.add(t);
        }
        return out;
    }

    private String clean(String line) {
        String t = line == null ? "" : line.strip();
        // 去掉前缀说明：改写1： / 变体 2: / query3.
        t = t.replaceFirst("^(改写|变体|版本|query)\\s*\\d{0,2}\\s*[.、:：)）]?\\s*", "");
        // 去掉 "1. " 这种编号 —— 必须带分隔符+空白，否则会把 "3.10 有什么新特性" 的 "3." 吃掉
        t = t.replaceFirst("^\\d{1,2}\\s*[.、)）]\\s+", "");
        // 去掉列表符号
        t = t.replaceFirst("^[-*•·]\\s*", "");
        // 去掉包裹的引号
        t = t.replaceAll("^[\"'「『“”‘’]+|[\"'」』“”‘’]+$", "");
        // 去掉句末标点（检索不看标点）
        t = t.replaceAll("[。；;，,、\\s]+$", "");
        return t.strip();
    }

    /** 归一化只用于去重：小写 + 去掉所有空白和标点 */
    private String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{P}]", "");
    }
}
