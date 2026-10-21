package com.raglab.retrieval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raglab.common.Stage;
import com.raglab.ingest.log.IngestLogBus;
import com.raglab.ingest.log.TaskLogger;
import com.raglab.ingest.service.ChromaClient;
import com.raglab.ingest.service.QwenEmbeddingClient;
import com.raglab.retrieval.dao.ContextTraceDao;
import com.raglab.retrieval.model.ContextResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文控制（阶段五 / 对应文章「三、优化4」）。
 *
 * 前面四个阶段解决的问题是「找到最相关的几片」，但没解决最后一个问题：
 * **这几片加起来可能有一两千字，而真正回答问题的只有两三句。**
 * 全都塞给大模型 → token 贵、噪音多、还容易被无关内容带偏。
 *
 * 所以这一步做的是：
 *   召回+重排（复用阶段四）→ 拆句 → 逐句打分 → 去重 + 限额选句 → 按原文顺序拼回去
 *
 * 关键在「按原文顺序拼回去」：如果直接把高分句子按分数排下来，
 * 读起来是跳跃的，大模型理解起来也费劲。
 *
 * 注意：这里的 token 数是**估算**（字 × 1.5），和阶段一保持同一口径。
 * 中文实际约 0.6~0.7 token/字，所以这个数偏保守（高估），用来控成本是安全的。
 */
@Service
public class ContextRetriever {

    private static final Logger log = LoggerFactory.getLogger(ContextRetriever.class);
    private static final int MAX_RECALL = 50;
    private static final double TOKEN_PER_CHAR = 1.5;

    private final QwenEmbeddingClient embeddingClient;
    private final ChromaClient chromaClient;
    private final Bm25Index bm25Index;
    private final RerankClient rerankClient;
    private final SentenceExtractor sentenceExtractor;
    private final ContextBuilder contextBuilder;
    private final ContextTraceDao traceDao;
    private final IngestLogBus logBus;
    private final ObjectMapper objectMapper;
    private final com.raglab.config.RagLabProperties props;

    public ContextRetriever(QwenEmbeddingClient embeddingClient, ChromaClient chromaClient,
                            Bm25Index bm25Index, RerankClient rerankClient,
                            SentenceExtractor sentenceExtractor, ContextBuilder contextBuilder,
                            ContextTraceDao traceDao, IngestLogBus logBus, ObjectMapper objectMapper,
                            com.raglab.config.RagLabProperties props) {
        this.embeddingClient = embeddingClient;
        this.chromaClient = chromaClient;
        this.bm25Index = bm25Index;
        this.rerankClient = rerankClient;
        this.sentenceExtractor = sentenceExtractor;
        this.contextBuilder = contextBuilder;
        this.traceDao = traceDao;
        this.logBus = logBus;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    private record RecallItem(int rank, String chromaId, int chunkIndex, long docId, String docName,
                              String heading, String content, double rerankScore) {
    }

    private record RecallOutcome(float[] queryVector, List<RecallItem> topChunks) {
    }

    public void run(String traceId, String rawQuery, int recallTopN, int topK, String strategyRaw, int maxChars) {
        TaskLogger logger = new TaskLogger(traceId, logBus);
        long start = System.currentTimeMillis();
        Stage stage = Stage.CONTEXT_RECALL;
        long stepStart = start;
        boolean success = false;

        SentenceExtractor.Strategy strategy;
        try {
            stage = Stage.CONTEXT_RECALL;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            if (rawQuery == null || rawQuery.isBlank()) {
                throw new IllegalArgumentException("问题不能为空");
            }
            String query = rawQuery.strip();
            int recallN = Math.max(5, Math.min(recallTopN, MAX_RECALL));
            int keepK = Math.max(1, Math.min(topK, 20));
            strategy = "KEYWORD".equalsIgnoreCase(strategyRaw)
                    ? SentenceExtractor.Strategy.KEYWORD : SentenceExtractor.Strategy.VECTOR;
            int charLimit = maxChars > 0 ? maxChars : props.context().maxChars();

            logger.info(stage, "问题：" + query);
            logger.info(stage, "召回 Top-%d → 重排取 Top-%d → 拆句压缩（策略 %s，上限 %d 字）"
                    .formatted(recallN, keepK, strategy.name(), charLimit));

            // ---------- 召回 + 重排 ----------
            long recallStart = System.currentTimeMillis();
            RecallOutcome ro = recallAndRerank(query, recallN, keepK);
            if (ro.topChunks().isEmpty()) {
                throw new IllegalStateException("召回为空，检查阶段一是否已入库文档");
            }
            long recallCost = System.currentTimeMillis() - recallStart;
            logger.ok(stage, "拿到 %d 片源切片，耗时 %dms".formatted(ro.topChunks().size(), recallCost),
                    previewChunks(ro.topChunks()), recallCost);

            // ---------- 拆句 ----------
            stage = Stage.CONTEXT_SPLIT;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            List<SentenceExtractor.ChunkRef> refs = new ArrayList<>();
            for (RecallItem it : ro.topChunks()) {
                refs.add(new SentenceExtractor.ChunkRef(it.rank(), it.chromaId(), it.docId(),
                        it.docName(), it.chunkIndex(), it.heading(), it.rerankScore(), it.content()));
            }
            // 先拆一遍只为统计（真正的拆句在 extractor 内部，避免重复计算）
            int originChars = refs.stream().mapToInt(r -> r.content().length()).sum();
            logger.ok(stage, "源切片共 %d 字，开始逐片拆句".formatted(originChars),
                    "拆句规则：按 。！？；切分；代码块和表格行整体保留；<8 字的碎片并到下一句",
                    System.currentTimeMillis() - stepStart);

            // ---------- 句子打分 ----------
            stage = Stage.CONTEXT_SCORE;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            long scoreStart = System.currentTimeMillis();
            List<SentenceExtractor.ScoredSentence> scored =
                    sentenceExtractor.extract(query, ro.queryVector(), refs, strategy);
            long scoreCost = System.currentTimeMillis() - scoreStart;
            if (scored.isEmpty()) {
                throw new IllegalStateException("拆句结果为空，源切片里没有可用的句子");
            }

            Map<String, Integer> perChunk = new HashMap<>();
            for (SentenceExtractor.ScoredSentence s : scored) {
                perChunk.merge(s.chromaId(), 1, Integer::sum);
            }
            int maxScoreIdx = 0;
            for (int i = 1; i < scored.size(); i++) {
                if (scored.get(i).score() > scored.get(maxScoreIdx).score()) {
                    maxScoreIdx = i;
                }
            }
            logger.ok(stage, "共 %d 句完成打分，耗时 %dms".formatted(scored.size(), scoreCost),
                    ("策略            : %s\n" +
                            "各片句子数      : %s\n" +
                            "最高分          : %.4f（第 %d 片第 %d 句）\n\n" +
                            "VECTOR 策略的分数是余弦相似度（0.4~0.7 算相关）。\n" +
                            "为了控制耗时，先用关键词分粗筛出 30 句，只对这些做 embedding 精排 ——\n" +
                            "否则 80 句要发 8 批请求，十几秒就出去了。\n\n" +
                            "KEYWORD 策略是覆盖率加权，绝对值不可比，只看相对排序。")
                            .formatted(strategy.name(), perChunk,
                                    scored.get(maxScoreIdx).score(),
                                    scored.get(maxScoreIdx).sourceRank(),
                                    scored.get(maxScoreIdx).orderInChunk() + 1),
                    scoreCost);

            // ---------- 选句去重 ----------
            stage = Stage.CONTEXT_SELECT;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            ContextBuilder.Config cfg = new ContextBuilder.Config(
                    props.context().maxSentencesPerChunk(), charLimit, props.context().dedupThreshold());
            logger.info(stage, "规则：每片最多贡献 %d 句，两句相似度 ≥ %.2f 只留一条，总长 ≤ %d 字"
                    .formatted(cfg.maxSentencesPerChunk(), cfg.dedupThreshold(), cfg.maxChars()));
            ContextBuilder.Outcome outcome = contextBuilder.build(scored, cfg);
            logger.ok(stage, "选中 %d 句（去重丢弃 %d 句，超长丢弃 %d 句）"
                            .formatted(outcome.selected().size(), outcome.droppedDuplicates(), outcome.droppedByLimit()),
                    "去重是必要的：阶段一的 overlap 会让相邻切片开头重复几十字，不处理等于白压缩。",
                    System.currentTimeMillis() - stepStart);

            // ---------- 组装上下文 ----------
            stage = Stage.CONTEXT_BUILD;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            StringBuilder ctx = new StringBuilder();
            List<ContextResult.Sentence> selectedModels = new ArrayList<>();
            int order = 1;
            for (SentenceExtractor.ScoredSentence s : outcome.selected()) {
                if (ctx.length() > 0) {
                    ctx.append('\n');
                }
                ctx.append(s.text());
                selectedModels.add(new ContextResult.Sentence(order++, s.text(), s.chromaId(),
                        s.docId(), s.docName(), s.chunkIndex(), s.heading(), s.sourceRank(),
                        s.score(), s.text().length()));
            }
            String contextText = ctx.toString();
            int finalChars = contextText.length();
            int originTokens = (int) Math.ceil(originChars * TOKEN_PER_CHAR);
            int finalTokens = (int) Math.ceil(finalChars * TOKEN_PER_CHAR);
            double ratio = originChars == 0 ? 0 : Math.round((double) finalChars / originChars * 1000) / 1000.0;

            logger.ok(stage, "上下文组装完成：%d 字 → %d 字（压缩到 %.1f%%），估算 %d → %d tokens"
                            .formatted(originChars, finalChars, ratio * 100, originTokens, finalTokens),
                    "拼接时按原文顺序（文档 → 切片 → 片内序号），不是按分数。\n" +
                            "否则句子顺序是跳的，大模型读起来很费劲。",
                    System.currentTimeMillis() - stepStart);

            // 逐句打印，方便肉眼检查抽得对不对
            for (ContextResult.Sentence s : selectedModels) {
                logger.info(stage, "#%-2d [%.4f] %s  ·  %s".formatted(
                        s.order(), s.score(), abbreviate(s.text(), 90), s.heading().isBlank() ? "无标题" : s.heading()));
            }

            // ---------- 完成 ----------
            stage = Stage.CONTEXT_DONE;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            List<ContextResult.SourceChunk> sources = new ArrayList<>();
            Map<String, Integer> selectedPerChunk = new HashMap<>();
            for (ContextResult.Sentence s : selectedModels) {
                selectedPerChunk.merge(s.chromaId(), 1, Integer::sum);
            }
            for (RecallItem it : ro.topChunks()) {
                sources.add(new ContextResult.SourceChunk(it.rank(), it.chromaId(), it.docId(),
                        it.docName(), it.chunkIndex(), it.heading(), it.rerankScore(), it.content(),
                        it.content().length(), perChunk.getOrDefault(it.chromaId(), 0),
                        selectedPerChunk.getOrDefault(it.chromaId(), 0)));
            }
            ContextResult.Stats stats = new ContextResult.Stats(originChars, finalChars,
                    originTokens, finalTokens, ratio, scored.size(), selectedModels.size(),
                    outcome.droppedDuplicates(), outcome.droppedByLimit());
            ContextResult result = new ContextResult(sources, selectedModels, contextText, stats);

            traceDao.updateResult(traceId, originChars, finalChars, selectedModels.size(), ratio,
                    objectMapper.writeValueAsString(result));
            long totalCost = System.currentTimeMillis() - start;
            traceDao.finish(traceId, "SUCCESS", null, totalCost);
            logger.ok(stage, "完成：%d 片 %d 字 → %d 句 %d 字，总耗时 %dms"
                            .formatted(sources.size(), originChars, selectedModels.size(), finalChars, totalCost),
                    "这份 context 就是可以直接拼进 Prompt 的东西。", totalCost);
            success = true;

        } catch (Exception e) {
            long stepCost = System.currentTimeMillis() - stepStart;
            logger.fail(stage, "「" + stage.getLabel() + "」失败", e, targetOf(stage), stepCost);
            traceDao.finish(traceId, "FAILED", truncate(e.getMessage(), 1900),
                    System.currentTimeMillis() - start);
            log.error("上下文压缩失败 traceId={} stage={}", traceId, stage.name(), e);
        } finally {
            logBus.close(traceId, success ? "SUCCESS" : "FAILED");
        }
    }

    // ------------------------------------------------------------------

    /** 召回 + 重排，返回问题向量（后面句子打分要用）和 Top-K 切片 */
    private RecallOutcome recallAndRerank(String query, int recallN, int keepK) {
        QwenEmbeddingClient.BatchResult qbr = embeddingClient.embedBatch(List.of(query));
        float[] qv = qbr.vectors().get(0);

        String collectionId = chromaClient.collectionIdByName(chromaClient.collectionName());
        if (chromaClient.count(collectionId) == 0) {
            return new RecallOutcome(qv, List.of());
        }
        JsonNode vecRes = chromaClient.query(collectionId, qv, recallN);
        JsonNode ids = vecRes.path("ids").path(0);
        JsonNode dists = vecRes.path("distances").path(0);
        JsonNode docs = vecRes.path("documents").path(0);
        JsonNode metas = vecRes.path("metadatas").path(0);

        int rrfK = 60;
        Map<String, double[]> fuse = new LinkedHashMap<>();
        Map<String, RecallItem> pool = new HashMap<>();

        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i).asText();
            JsonNode meta = metas.get(i);
            fuse.computeIfAbsent(id, k -> new double[]{0, -1, 0, -1, 0});
            double[] v = fuse.get(id);
            v[0] += 1.0 / (rrfK + i + 1);
            v[1] = i + 1;
            v[2] = 1 - dists.get(i).asDouble();
            pool.putIfAbsent(id, new RecallItem(0, id,
                    meta == null ? -1 : meta.path("chunkIndex").asInt(-1),
                    parseLong(meta, "docId"), textOf(meta, "docName"), textOf(meta, "heading"),
                    docs.get(i).asText(""), 0));
        }

        Bm25Index.Freshness fresh = bm25Index.ensureFresh();
        List<Bm25Index.Hit> bm25 = bm25Index.search(fresh.snapshot(), query, recallN);
        for (int i = 0; i < bm25.size(); i++) {
            Bm25Index.DocMeta d = fresh.snapshot().docs().get(bm25.get(i).chunkId());
            if (d == null) {
                continue;
            }
            fuse.computeIfAbsent(d.chromaId(), k -> new double[]{0, -1, 0, -1, 0});
            double[] v = fuse.get(d.chromaId());
            v[0] += 1.0 / (rrfK + i + 1);
            v[3] = i + 1;
            v[4] = bm25.get(i).score();
            pool.putIfAbsent(d.chromaId(), new RecallItem(0, d.chromaId(), d.chunkIndex(),
                    d.docId(), d.docName(), d.heading(), d.content(), 0));
        }

        List<String> orderedIds = fuse.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
                .limit(recallN)
                .map(Map.Entry::getKey)
                .toList();

        List<RecallItem> candidates = new ArrayList<>();
        for (String id : orderedIds) {
            RecallItem m = pool.get(id);
            if (m != null) {
                candidates.add(m);
            }
        }
        if (candidates.isEmpty()) {
            return new RecallOutcome(qv, List.of());
        }

        // 重排
        List<String> texts = candidates.stream().map(RecallItem::content).toList();
        RerankClient.Result rr = rerankClient.rerank(query, texts);
        Map<Integer, Double> scoreByIndex = new HashMap<>();
        for (RerankClient.Score s : rr.scores()) {
            scoreByIndex.put(s.index(), s.relevance());
        }

        List<RecallItem> reordered = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            RecallItem c = candidates.get(i);
            reordered.add(new RecallItem(0, c.chromaId(), c.chunkIndex(), c.docId(), c.docName(),
                    c.heading(), c.content(), scoreByIndex.getOrDefault(i, 0.0)));
        }
        reordered.sort(Comparator.comparingDouble(RecallItem::rerankScore).reversed());

        List<RecallItem> top = new ArrayList<>();
        int rank = 1;
        for (RecallItem c : reordered) {
            if (top.size() >= keepK) {
                break;
            }
            top.add(new RecallItem(rank++, c.chromaId(), c.chunkIndex(), c.docId(), c.docName(),
                    c.heading(), c.content(), c.rerankScore()));
        }
        return new RecallOutcome(qv, top);
    }

    private String previewChunks(List<RecallItem> list) {
        StringBuilder sb = new StringBuilder("重排后的源切片：\n");
        for (RecallItem c : list) {
            sb.append("#").append(c.rank())
                    .append("  rerank ").append(String.format("%.4f", c.rerankScore()))
                    .append("  ").append(c.content().length()).append(" 字")
                    .append("  ·  ").append(abbreviate(c.content(), 50))
                    .append("\n");
        }
        return sb.toString();
    }

    private String targetOf(Stage stage) {
        return switch (stage) {
            case CONTEXT_RECALL -> "Qwen/Chroma/Rerank";
            case CONTEXT_SCORE -> "Qwen";
            default -> "Context";
        };
    }

    private long parseLong(JsonNode meta, String key) {
        if (meta == null || meta.isNull()) {
            return 0;
        }
        try {
            return Long.parseLong(meta.path(key).asText("0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String textOf(JsonNode meta, String key) {
        if (meta == null || meta.isNull()) {
            return "";
        }
        return meta.path(key).asText("");
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
