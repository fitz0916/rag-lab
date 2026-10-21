package com.raglab.rewrite.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raglab.common.Stage;
import com.raglab.config.RagLabProperties;
import com.raglab.ingest.log.IngestLogBus;
import com.raglab.ingest.log.TaskLogger;
import com.raglab.rewrite.dao.RewriteTraceDao;
import com.raglab.rewrite.model.RewriteHit;
import com.raglab.rewrite.model.RewriteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 查询重写流水线（阶段八 / 对应文章「三、优化1」）。
 *
 * 为什么这一步放在最后做？
 *   因为它是**唯一每次查询都要额外调一次 LLM** 的优化。
 *   混合检索、重排、压缩都是一次性成本或纯 CPU；改写是每次提问都要多花钱、多等一秒。
 *   所以必须有评测（阶段六）来回答「这一秒和这点钱，换来多少命中率」。
 *
 * 日志设计上有个重点：不只要打「最终 Top-K」，还要打
 *   - 每个变体召回了多少条、其中多少条是原查询没有的（衡量变体的价值）
 *   - 每条结果被几路召回（衡量结果的稳定程度）
 *   - 重写前后 Top-K 的差异（衡量这次改写到底值不值）
 * 没有这三样，"加了改写"就只是个感觉。
 */
@Service
public class RewriteRetriever {

    private static final Logger log = LoggerFactory.getLogger(RewriteRetriever.class);

    private final MultiQueryRetriever multi;
    private final RewriteTraceDao traceDao;
    private final IngestLogBus logBus;
    private final ObjectMapper objectMapper;
    private final RagLabProperties props;

    public RewriteRetriever(MultiQueryRetriever multi, RewriteTraceDao traceDao,
                            IngestLogBus logBus, ObjectMapper objectMapper, RagLabProperties props) {
        this.multi = multi;
        this.traceDao = traceDao;
        this.logBus = logBus;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    public void run(String traceId, String rawQuery, int variants, int topK, int recallN, boolean rerank) {
        TaskLogger logger = new TaskLogger(traceId, logBus);
        long start = System.currentTimeMillis();
        Stage stage = Stage.REWRITE_GEN;
        long stepStart = start;
        boolean success = false;

        try {
            // ================= 1. 生成变体 =================
            stage = Stage.REWRITE_GEN;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            if (rawQuery == null || rawQuery.isBlank()) {
                throw new IllegalArgumentException("问题不能为空");
            }
            String query = rawQuery.strip();
            logger.info(stage, "原问题：" + query);
            logger.info(stage, "模型 %s，temperature %.2f（改写要比问答更发散），目标 %d 个变体"
                    .formatted("qwen-plus", props.rewrite().temperature(), variants));
            logger.info(stage, "重要：改写不是用向量做的，是让大模型直接生成文本变体");

            MultiQueryRetriever.Prepared prepared = multi.prepare(query, variants);
            List<String> queries = prepared.queries();
            long genMs = prepared.genMs();
            long embedMs = System.currentTimeMillis() - stepStart - genMs;

            if (queries.size() == 1) {
                logger.warn(stage, "模型没有给出可用的变体，本次退化为「只用原查询」",
                        "可能原因：① 模型没按「每行一个」输出 ② 生成的变体与原问题重复被去重掉了\n" +
                                "这种情况下收益必然为 0，正好可以用它当对照组。");
            }
            for (int i = 0; i < queries.size(); i++) {
                logger.info(stage, "  [%s] %s".formatted(i == 0 ? "原查询" : "变体" + i, queries.get(i)));
            }
            logger.ok(stage, "共 %d 个查询（原查询 + %d 个变体）".formatted(queries.size(), queries.size() - 1),
                    ("改写耗时 %dms（prompt %d / completion %d tokens）\n" +
                            "embedding %dms —— **%d 个查询只发了 1 次批量请求**，不是 %d 次\n\n" +
                            "如果逐个循环调用 embedding，这里会慢 %d 倍、也多花 %d 倍的钱。")
                            .formatted(genMs, prepared.promptTokens(), prepared.completionTokens(),
                                    embedMs, queries.size(), queries.size(),
                                    queries.size(), queries.size() - 1),
                    System.currentTimeMillis() - stepStart);

            // ================= 2. 多变体召回 =================
            stage = Stage.REWRITE_RECALL;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            logger.info(stage, "每个查询各跑一遍「向量 + BM25 + RRF」，各取 Top-%d".formatted(recallN));

            MultiQueryRetriever.SearchOutcome searched = multi.search(prepared, recallN);
            for (MultiQueryRetriever.VariantStat s : searched.stats()) {
                String tag = s.index() == 0 ? "原查询" : "变体" + s.index();
                if (s.index() == 0) {
                    logger.info(stage, "  %-6s 召回 %d 条".formatted(tag, s.hits()));
                } else {
                    logger.info(stage, "  %-6s 召回 %d 条，其中 %d 条是原查询没召回的".formatted(
                            tag, s.hits(), s.newHits()));
                }
            }
            logger.ok(stage, "候选池共 %d 条（去重后）".formatted(searched.candidatePool()),
                    "「新增条数」是判断变体价值最直接的指标：全是 0 说明这个变体在说废话。",
                    System.currentTimeMillis() - stepStart);

            // ================= 3. 跨变体融合 =================
            stage = Stage.REWRITE_MERGE;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            int weightOriginal = Math.max(1, props.rewrite().originalWeight());
            logger.info(stage, "公式：score = Σ weight / (60 + rank)，原查询 weight=%d，变体 weight=1"
                    .formatted(weightOriginal));
            logger.info(stage, "原查询权重更高，是防止改写跑偏把结果带歪；被多路同时命中的条目会自然浮上来");

            List<RewriteHit> fused = multi.fuse(searched, Math.max(recallN, topK));
            for (int i = 0; i < Math.min(5, fused.size()); i++) {
                RewriteHit h = fused.get(i);
                logger.info(stage, "  #%d 融合 %.6f  %s  %s".formatted(
                        h.rank(), h.fusedScore(), sourcesText(h.from()), abbreviate(h.content(), 46)),
                        "被 %d 路召回：%s\n原查询里的排名：%s".formatted(
                                h.hitCount(), sourcesText(h.from()),
                                h.baselineRank() > 0 ? "#" + h.baselineRank() : "未召回"));
            }
            logger.ok(stage, "融合完成，候选 %d 条".formatted(fused.size()),
                    System.currentTimeMillis() - stepStart);

            // ================= 4. 可选重排 =================
            List<RewriteHit> merged;
            boolean reranked = false;
            if (rerank) {
                stage = Stage.REWRITE_RERank;
                stepStart = System.currentTimeMillis();
                logger.enter(stage);
                logger.info(stage, "用**原问题**做交叉编码器精排（不是用变体）—— 用户问的是原问题");
                try {
                    List<RewriteHit> before = fused;
                    merged = multi.rerank(query, fused, topK);
                    reranked = true;
                    int up = 0;
                    int down = 0;
                    for (RewriteHit h : merged) {
                        int oldRank = indexOf(before, h.chromaId());
                        if (oldRank > h.rank()) {
                            up++;
                        } else if (oldRank < h.rank()) {
                            down++;
                        }
                        if (h.rank() <= 3) {
                            logger.info(stage, "  #%d rerank %.4f  %s  %s".formatted(
                                    h.rank(), h.rerankScore(),
                                    oldRank > 0 ? "融合#" + oldRank + " → " + delta(oldRank, h.rank()) : "",
                                    abbreviate(h.content(), 44)));
                        }
                    }
                    logger.ok(stage, "重排完成：上升 %d 条，下降 %d 条".formatted(up, down),
                            System.currentTimeMillis() - stepStart);
                } catch (Exception e) {
                    // 重排是「锦上添花」，失败了不该让整个查询失败
                    logger.fail(stage, "重排失败，退化为融合顺序（结果仍可用）", e, "DashScope-Rerank",
                            System.currentTimeMillis() - stepStart);
                    merged = take(fused, topK);
                }
            } else {
                merged = take(fused, topK);
            }

            // ================= 5. 完成 =================
            stage = Stage.REWRITE_DONE;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);

            List<RewriteHit> baseline = take(searched.perQuery().isEmpty()
                    ? List.of() : searched.perQuery().get(0), topK);

            Set<String> mergedIds = new LinkedHashSet<>();
            for (RewriteHit h : merged) {
                mergedIds.add(h.chromaId());
            }
            Set<String> baseIds = new LinkedHashSet<>();
            for (RewriteHit h : baseline) {
                baseIds.add(h.chromaId());
            }
            List<RewriteHit> gained = new ArrayList<>();
            for (RewriteHit h : merged) {
                if (!baseIds.contains(h.chromaId())) {
                    gained.add(h);
                }
            }
            List<RewriteHit> lost = new ArrayList<>();
            for (RewriteHit h : baseline) {
                if (!mergedIds.contains(h.chromaId())) {
                    lost.add(h);
                }
            }

            StringBuilder cmp = new StringBuilder();
            cmp.append("重写前 Top-1：").append(baseline.isEmpty() ? "（空）" : abbreviate(baseline.get(0).content(), 50)).append("\n");
            cmp.append("重写后 Top-1：").append(merged.isEmpty() ? "（空）" : abbreviate(merged.get(0).content(), 50)).append("\n\n");
            if (gained.isEmpty() && lost.isEmpty()) {
                cmp.append("Top-%d 成员完全没变 —— 这次改写没有带来任何新东西。\n".formatted(topK));
                cmp.append("别急着下结论：也可能是这个问题本来就检索得很好（简单题改写收益天然小）。");
            } else {
                cmp.append("新进入 Top-%d 的（%d 条）：\n".formatted(topK, gained.size()));
                for (RewriteHit h : gained) {
                    cmp.append("  ").append(sourcesText(h.from())).append("  ")
                            .append(abbreviate(h.content(), 50)).append("\n");
                }
                cmp.append("\n被挤出 Top-%d 的（%d 条）：\n".formatted(topK, lost.size()));
                for (RewriteHit h : lost) {
                    cmp.append("  原查询#").append(h.baselineRank()).append("  ")
                            .append(abbreviate(h.content(), 50)).append("\n");
                }
                cmp.append("\n注意：多出来的不一定更好。改写会引入语义漂移，");
                cmp.append("所以原查询权重更高，而且这一步值不值必须靠评测（阶段六）判断。");
            }
            logger.ok(stage, "Top-%d 对比：新增 %d 条，移出 %d 条".formatted(topK, gained.size(), lost.size()),
                    cmp.toString(), System.currentTimeMillis() - stepStart);

            List<RewriteResult.Variant> variantModels = new ArrayList<>();
            for (MultiQueryRetriever.VariantStat s : searched.stats()) {
                if (s.index() == 0) {
                    continue;
                }
                variantModels.add(new RewriteResult.Variant(s.index(), s.text(), s.hits(), s.newHits()));
            }
            RewriteResult.Stats stats = new RewriteResult.Stats(queries.size() - 1,
                    searched.candidatePool(), merged.size(), prepared.promptTokens(), reranked,
                    gained.size(), lost.size());
            RewriteResult result = new RewriteResult(query, variantModels,
                    compact(merged), compact(baseline), stats);

            traceDao.updateResult(traceId, queries.size() - 1, prepared.promptTokens(),
                    objectMapper.writeValueAsString(result));
            long totalCost = System.currentTimeMillis() - start;
            traceDao.finish(traceId, "SUCCESS", null, totalCost);

            logger.ok(stage, "完成：%d 个查询、候选 %d 条、输出 %d 条，总耗时 %dms（其中改写 %dms）"
                            .formatted(queries.size(), searched.candidatePool(), merged.size(),
                                    totalCost, genMs),
                    "下一步建议：把「大模型瞎编怎么办」这类口语问题拿去阶段六跑一遍 REWRITE 策略，\n" +
                            "看 Hit@3 到底涨没涨 —— 改写是唯一每次都要多花钱的优化，必须有数据支撑。",
                    totalCost);
            success = true;

        } catch (Exception e) {
            long stepCost = System.currentTimeMillis() - stepStart;
            logger.fail(stage, "「" + stage.getLabel() + "」失败", e, targetOf(stage), stepCost);
            traceDao.finish(traceId, "FAILED", truncate(e.getMessage(), 1900),
                    System.currentTimeMillis() - start);
            log.error("查询重写失败 traceId={} stage={}", traceId, stage.name(), e);
        } finally {
            logBus.close(traceId, success ? "SUCCESS" : "FAILED");
        }
    }

    // ------------------------------------------------------------------

    private List<RewriteHit> compact(List<RewriteHit> list) {
        List<RewriteHit> out = new ArrayList<>(list.size());
        for (RewriteHit h : list) {
            out.add(h.compact());
        }
        return out;
    }

    private List<RewriteHit> take(List<RewriteHit> list, int n) {
        return list.size() <= n ? list : new ArrayList<>(list.subList(0, n));
    }

    private int indexOf(List<RewriteHit> list, String chromaId) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).chromaId().equals(chromaId)) {
                return i + 1;
            }
        }
        return 0;
    }

    private String delta(int oldRank, int newRank) {
        int d = oldRank - newRank;
        return d > 0 ? "↑" + d : d < 0 ? "↓" + (-d) : "—";
    }

    private String sourcesText(List<Integer> from) {
        if (from == null || from.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < from.size(); i++) {
            if (i > 0) {
                sb.append("+");
            }
            sb.append(from.get(i) == 0 ? "原" : "变" + from.get(i));
        }
        return sb.append("]").toString();
    }

    private String targetOf(Stage stage) {
        return switch (stage) {
            case REWRITE_GEN -> "DashScope-Chat";
            case REWRITE_RERank -> "DashScope-Rerank";
            default -> "Rewrite";
        };
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String one = s.replace("\n", " ⏎ ").strip();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
